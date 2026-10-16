package me.niteshh.redcake.replication.replica;

import lombok.extern.slf4j.Slf4j;
import me.niteshh.redcake.config.TlsConfig;
import me.niteshh.redcake.security.TlsSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Holds the replica's outbound TCP connection to its primary, optionally
 * wrapped in TLS ({@code --tls-replication}). With TLS the primary's
 * certificate is verified against the trust store <em>including its host
 * name</em>, so a replica cannot be tricked into syncing from an impostor.
 * Methods are synchronized because the sync thread connects/reads while a
 * shutdown hook may close it concurrently.
 *
 * <p>The (slow, up to 3 s) connect itself happens <em>outside</em> the monitor: when a primary's machine
 * disappears, packets to it are dropped silently, and a failover that needs {@link #close()} must not wait
 * for the dead host's connect attempt to time out. {@code close()} aborts a connect in progress instead.
 */
@Slf4j
@Component
public class PrimaryConnection {

    private final SSLContext sslContext;

    private Socket socket;
    /** The socket {@link #connect} is still establishing (so {@link #close()} can abort it). */
    private Socket connecting;
    private String host;
    private int port;

    @Autowired
    public PrimaryConnection(TlsConfig tlsConfig) {
        this.sslContext = tlsConfig.isReplicationTls() ? TlsSupport.createContext(tlsConfig) : null;
    }

    /** Plain-TCP connection (tests and the default). */
    public PrimaryConnection() {
        this.sslContext = null;
    }

    /** Opens the connection (3 s connect timeout, keepalive, no Nagle) unless already connected. */
    public void connect(String host, int port) {
        Socket plain = new Socket();
        synchronized (this) {
            if (isConnected()) {
                closeQuietly(plain);
                return;
            }
            this.host = host;
            this.port = port;
            this.connecting = plain;
        }
        try {
            plain.setKeepAlive(true);
            plain.setTcpNoDelay(true);
            plain.connect(new InetSocketAddress(host, port), 3_000);

            Socket connected = plain;
            if (sslContext != null) {
                SSLSocket tls = (SSLSocket) sslContext.getSocketFactory()
                        .createSocket(plain, host, port, true);
                SSLParameters parameters = tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS"); // verify the host name
                tls.setSSLParameters(parameters);
                tls.setSoTimeout(10_000); // bound the handshake
                tls.startHandshake();
                connected = tls;
            }
            synchronized (this) {
                if (connecting != plain) { // close() was called while we were connecting
                    closeQuietly(connected);
                    throw new IOException("connect was cancelled");
                }
                connecting = null;
                this.socket = connected;
            }
        } catch (IOException e) {
            closeQuietly(plain);
            synchronized (this) {
                if (connecting == plain) {
                    connecting = null;
                }
            }
            throw new RuntimeException("Failed to connect to primary at " + host + ":" + port, e);
        }
    }

    public synchronized boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    public synchronized Socket getSocket() {
        return socket;
    }

    /** Closes the connection (or aborts one being established); safe to call repeatedly. */
    public synchronized void close() {
        if (connecting != null) {
            closeQuietly(connecting);
            connecting = null;
        }
        if (socket == null) {
            return;
        }
        closeQuietly(socket);
        socket = null;
        log.info("Disconnected from primary at {}:{}", host, port);
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (Exception e) {
            // already closed
        }
    }
}
