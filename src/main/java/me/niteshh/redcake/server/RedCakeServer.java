package me.niteshh.redcake.server;


import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class RedCakeServer {

    private static final int PORT = 6379;

    @PostConstruct
    public void start() {
        Thread serverThread = new Thread(() -> {
            try( ServerSocket serverSocket = new ServerSocket(PORT)) {  // @ServerSocket primarily waits for incoming connections.
                serverSocket.setReuseAddress(true); // Allow the socket to be bound even if a previous connection is in a TIME_WAIT state
                // means that the socket can be reused immediately after the previous connection is closed,
                // without waiting for the TIME_WAIT period to expire and without encountering the "Address already in use" error
                // this means that the server can be restarted quickly without waiting for the operating system to release the port.
                System.out.println("RedCake server started on port " + PORT);

                // Create a thread pool to handle client connections concurrently
                ExecutorService executor = Executors.newCachedThreadPool();

                while (true) {
                    Socket clientSocket = serverSocket.accept();  // @Socket represents an individual established connection. @accept() waits for a client to connect.
                    System.out.println("Client connected: " + clientSocket.getRemoteSocketAddress());
                    executor.submit(() -> handleClient(clientSocket));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

        });
        serverThread.setName("RedCakeServerThread");
        serverThread.start();
    }

    private void handleClient(Socket clientSocket) {
        // TODO: Implement client handling logic

        try(clientSocket){
            byte[] buffer = new byte[1024];

            int bytesRead = clientSocket.getInputStream().read(buffer);
            /**
             * "Give me whatever bytes are currently available from this TCP stream,
             * up to the size of my buffer,
             * and wait if necessary until at least some data is available or the stream closes."*/
            // Disadvantage:
            // read(buffer) is a blocking operation, so the current thread may wait
            // until data becomes available or the client closes the connection.
            // Since this implementation calls read() only once, it processes only
            // the bytes returned by that single read operation.
            //
            // If the client sends more data later, or if the data is larger than
            // the buffer, the remaining data is not processed because we never
            // call read() again. The remaining bytes may still be buffered by the
            // socket, but they are effectively ignored by this implementation.
            //
            // Also, a single read() does not guarantee that we receive one complete
            // message/command because TCP is a byte stream and does not preserve
            // message boundaries.


            if (bytesRead != -1) {
                String request = new String(buffer, 0, bytesRead);
                System.out.println("Received request: " + request);

                OutputStream outputStream = clientSocket.getOutputStream();
                outputStream.write("+PONG\r\n".getBytes());
                outputStream.flush();
            }

        }catch (Exception e) {
            System.out.println("Error handling client: " + e.getMessage());
        }
    }

}
