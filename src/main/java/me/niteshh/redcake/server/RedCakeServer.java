package me.niteshh.redcake.server;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import me.niteshh.redcake.config.RedCakeServerConfig;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

@Component
public class RedCakeServer {

    private final RedCakeServerConfig serverConfig;
    private final ClientHandler clientHandler;
    private final ExecutorService executor =
            Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final Semaphore connectionLimit = new Semaphore(10_000);

    private volatile ServerSocket serverSocket;
    private volatile Thread serverThread;
    private volatile boolean running;

    public RedCakeServer(
            ClientHandler clientHandler,
            RedCakeServerConfig serverConfig
    ) {
        this.serverConfig = serverConfig;
        this.clientHandler = clientHandler;
    }

    /**
     * Starts the RedCake server.
     * This method initializes the server socket, binds it to the specified address and port,
     * and starts a thread to accept incoming client connections.
     * If the server is already running, this method does nothing.
     */
    @PostConstruct
    public synchronized void start() { // synchronized to ensure thread safety when starting the server
        if (running) {
            return;
        }

        try {
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true); // what is this setReuseAddress(true) do? It allows the socket to be bound to an address that is already in use.
            socket.bind(
                    new InetSocketAddress(
                            serverConfig.getBindAddress(),
                            serverConfig.getPort()
                    )
            );

            serverSocket = socket;
            running = true;
            serverThread = Thread.ofPlatform()
                    .name("RedCakeAcceptThread")
                    .start(this::acceptClients);
            System.out.println("RedCake server started on port " + serverConfig.getPort());
        } catch (Exception e) {
            closeQuietly(serverSocket);
            serverSocket = null;
            throw new IllegalStateException(
                    "Unable to bind RedCake to port "
                            + serverConfig.getPort()
                            + ". The port may already be in use.", e
            );
        }
    }

    /**
     * Accepts incoming client connections in a loop.
     * This method runs in a separate thread and continuously listens for new client connections.
     * When a client connects, it checks if the connection limit has been reached. If not, it adds the client to the set of active clients
     * and submits a task to handle the client using the provided ClientHandler.
     * If an exception occurs while accepting connections, it logs the error and stops accepting further connections.
     */
    private void acceptClients() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept(); // Accepts a new client connection. This method blocks until a client connects.
                if (!connectionLimit.tryAcquire()) { // Checks if the connection limit has been reached. If not, it acquires a permit to allow the new connection.
                    closeQuietly(clientSocket);
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
            } catch (Exception e) {
                if (running) {
                    System.err.println(
                            "Accept loop stopped: " + e.getMessage()
                    );
                }
                return;
            }
        }
    }

    /**
     * Stops the RedCake server.
     * This method stops accepting new client connections and closes all active client connections.
     * It also shuts down the executor service used to handle client requests.
     * If the server is not running, this method does nothing.
     */
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
        System.out.println("RedCake server stopped.");
    }

    private void closeQuietly(ServerSocket socket) {
        if (socket == null || socket.isClosed()) {
            return;
        }

        try {
            socket.close();
        } catch (Exception e) {
            System.err.println(
                    "Failed to close server socket: " + e.getMessage()
            );
        }
    }

    private void closeQuietly(Socket socket) {
        if (socket == null || socket.isClosed()) {
            return;
        }

        try {
            socket.close();
        } catch (Exception e) {
            System.err.println(
                    "Failed to close client socket: " + e.getMessage()
            );
        }
    }
}
