package me.niteshh.redcake.server;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import me.niteshh.redcake.config.RedCakeAuthConfig;
import me.niteshh.redcake.config.RedCakeServerConfig;
import me.niteshh.redcake.config.TlsConfig;
import me.niteshh.redcake.security.TlsSupport;
import me.niteshh.redcake.stats.ServerStats;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLServerSocket;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * The TCP listener: binds the configured address, accepts connections and
 * hands each one to {@link ClientHandler} on a fresh virtual thread.
 *
 * <h3>TLS</h3>
 * With {@code --tls-keystore} the listener speaks TLS ({@code rediss://} style) for
 * clients and replicas alike; {@code --tls-client-auth} requires client certificates.
 *
 * <h3>Safety limits</h3>
 * <ul>
 *   <li><b>maxclients</b> - a {@link Semaphore} caps simultaneous clients;
 *       extra connections get {@code -ERR max number of clients reached}
 *       and are closed instead of exhausting memory.</li>
 *   <li><b>idle timeout</b> - {@code --timeout} becomes the socket read
 *       timeout, evicting silent clients (slowloris).</li>
 *   <li><b>protected mode</b> - refuses to start on a non-loopback address
 *       without an API key unless {@code --allow-insecure} is given, so a
 *       careless {@code --bind 0.0.0.0} cannot expose an open database.</li>
 * </ul>
 *
 * Binding happens synchronously in {@link #start()} so a port conflict fails
 * application startup immediately with a clear message.
 */
@Slf4j
@Component
public class RedCakeServer {

    private final RedCakeServerConfig serverConfig;
    private final RedCakeAuthConfig authConfig;
    private final TlsConfig tlsConfig;
    private final ClientHandler clientHandler;
    private final ServerStats serverStats;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final Semaphore connectionLimit;

    private volatile ServerSocket serverSocket;
    private volatile Thread serverThread;
    private volatile boolean running;

    /** Plain-TCP server (tests). */
    public RedCakeServer(
            ClientHandler clientHandler,
            RedCakeServerConfig serverConfig,
            RedCakeAuthConfig authConfig,
            ServerStats serverStats
    ) {
        this(clientHandler, serverConfig, authConfig, serverStats, TlsConfig.disabled());
    }

    @Autowired
    public RedCakeServer(
            ClientHandler clientHandler,
            RedCakeServerConfig serverConfig,
            RedCakeAuthConfig authConfig,
            ServerStats serverStats,
            TlsConfig tlsConfig
    ) {
        this.serverConfig = serverConfig;
        this.authConfig = authConfig;
        this.tlsConfig = tlsConfig;
        this.clientHandler = clientHandler;
        this.serverStats = serverStats;
        this.connectionLimit = new Semaphore(serverConfig.getMaxClients());
    }

    /** Validates the security posture, binds the port and starts the accept thread. */
    @PostConstruct
    public synchronized void start() {
        if (running) {
            return;
        }

        if (serverConfig.isNetworkExposed()
                && !authConfig.isEnabled()
                && !serverConfig.isAllowInsecure()) {
            throw new IllegalStateException(
                    "Refusing to listen on " + serverConfig.getBindAddress()
                            + " without authentication. Set REDCAKE_API_KEY (or --api-key-file), "
                            + "bind to 127.0.0.1, or pass --allow-insecure if the network is trusted."
            );
        }

        try {
            ServerSocket socket = createServerSocket();
            socket.setReuseAddress(true); // lets a restart bind while old connections sit in TIME_WAIT
            socket.bind(new InetSocketAddress(serverConfig.getBindAddress(), serverConfig.getPort()));

            serverSocket = socket;
            running = true;
            serverThread = Thread.ofPlatform()
                    .name("RedCakeAcceptThread")
                    .start(this::acceptClients);
            log.info("RedCake listening on {}:{} (maxclients={}, timeout={}s, auth={}, tls={})",
                    serverConfig.getBindAddress(), serverConfig.getPort(),
                    serverConfig.getMaxClients(), serverConfig.getIdleTimeoutSeconds(),
                    authConfig.isEnabled() ? "on" : "off",
                    tlsConfig.isServerTlsEnabled() ? "on" : "off");
        } catch (Exception e) {
            closeQuietly(serverSocket);
            serverSocket = null;
            throw new IllegalStateException(
                    "Unable to bind RedCake to " + serverConfig.getBindAddress() + ":"
                            + serverConfig.getPort() + ". The port may already be in use.", e);
        }
    }

    /**
     * A plain or TLS listener. With TLS the handshake happens lazily on the
     * first read inside the client's own virtual thread, so a slow or hostile
     * handshake can never block the accept loop. Mutual TLS is switched on
     * with {@code --tls-client-auth}.
     */
    private ServerSocket createServerSocket() throws IOException {
        if (!tlsConfig.isServerTlsEnabled()) {
            return new ServerSocket();
        }
        SSLServerSocket socket = (SSLServerSocket) TlsSupport.createContext(tlsConfig)
                .getServerSocketFactory().createServerSocket();
        socket.setNeedClientAuth(tlsConfig.isClientAuth());
        return socket;
    }

    /**
     * Accept loop (own platform thread). A transient accept error (e.g. out of
     * file descriptors) is logged and retried after a short pause instead of
     * silently killing the listener.
     */
    private void acceptClients() {
        while (running) {
            Socket clientSocket;
            try {
                clientSocket = serverSocket.accept();
            } catch (IOException e) {
                if (!running || serverSocket == null || serverSocket.isClosed()) {
                    return;
                }
                log.error("Accept failed, retrying: {}", e.getMessage());
                sleepQuietly(100);
                continue;
            }

            if (!connectionLimit.tryAcquire()) {
                serverStats.connectionRejected();
                rejectConnection(clientSocket);
                continue;
            }

            try {
                clientSocket.setTcpNoDelay(true);
                clientSocket.setKeepAlive(true);
                clientSocket.setSoTimeout(serverConfig.getIdleTimeoutSeconds() * 1000);
            } catch (IOException e) {
                closeQuietly(clientSocket);
                connectionLimit.release();
                continue;
            }

            clients.add(clientSocket);
            executor.submit(() -> {
                try {
                    clientHandler.handleClient(clientSocket);
                } finally {
                    clients.remove(clientSocket);
                    connectionLimit.release();
                }
            });
        }
    }

    /** Tells an over-limit client why it is being dropped, then closes it. */
    private void rejectConnection(Socket socket) {
        try (socket) {
            OutputStream out = socket.getOutputStream();
            out.write("-ERR max number of clients reached\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        } catch (IOException ignored) {
            // the client is already gone
        }
    }

    /** Stops accepting, closes all clients and the virtual-thread executor. Idempotent. */
    @PreDestroy
    public synchronized void stop() {
        if (!running && serverSocket == null) {
            return;
        }

        running = false;
        closeQuietly(serverSocket);
        serverSocket = null;

        clients.forEach(this::closeQuietly);
        clients.clear();

        if (serverThread != null) {
            try {
                serverThread.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            serverThread = null;
        }

        executor.close();
        log.info("RedCake server stopped");
    }

    private void closeQuietly(ServerSocket socket) {
        if (socket == null || socket.isClosed()) {
            return;
        }
        try {
            socket.close();
        } catch (Exception e) {
            log.warn("Failed to close server socket: {}", e.getMessage());
        }
    }

    private void closeQuietly(Socket socket) {
        if (socket == null || socket.isClosed()) {
            return;
        }
        try {
            socket.close();
        } catch (Exception e) {
            log.warn("Failed to close client socket: {}", e.getMessage());
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
