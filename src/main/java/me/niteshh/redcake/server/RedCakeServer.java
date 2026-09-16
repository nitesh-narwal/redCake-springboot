package me.niteshh.redcake.server;


import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class RedCakeServer {

    private static final int PORT = 6379;
    private final ClientHandler clientHandler;
    private ServerSocket serverSocket;

    // Create a thread pool to handle client connections concurrently
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public RedCakeServer(ClientHandler clientHandler) {
        this.clientHandler = clientHandler;
    }

    @PostConstruct
    public void start() {
        Thread serverThread = new Thread(() -> {
            try {
                // @ServerSocket primarily waits for incoming connections.
                this.serverSocket = new ServerSocket(PORT);
                this.serverSocket.setReuseAddress(true); // Allow the socket to be bound even if a previous connection is in a TIME_WAIT state
                // means that the socket can be reused immediately after the previous connection is closed,
                // without waiting for the TIME_WAIT period to expire and without encountering the "Address already in use" error
                // this means that the server can be restarted quickly without waiting for the operating system to release the port.
                System.out.println("RedCake server started on port " + PORT);

                while (!this.serverSocket.isClosed()) {
                    Socket clientSocket = this.serverSocket.accept();  // @Socket represents an individual established connection. @accept() waits for a client to connect.
                    System.out.println("Client connected: " + clientSocket.getRemoteSocketAddress());
                    executor.submit(() -> clientHandler.handleClient(clientSocket));
                }
            } catch (Exception e) {
                if (this.serverSocket == null || !this.serverSocket.isClosed()) {
                    e.printStackTrace();
                }
            }

        });
        serverThread.setName("RedCakeServerThread");
        serverThread.start();
    }

    @PreDestroy
    public void stop() {

        System.out.println("Stopping RedCake server...");

        try {
            if (this.serverSocket != null && !this.serverSocket.isClosed()) {
                this.serverSocket.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        executor.shutdownNow();

        System.out.println("RedCake server stopped.");
    }

}
