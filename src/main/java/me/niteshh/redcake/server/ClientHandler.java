package me.niteshh.redcake.server;

import lombok.AllArgsConstructor;
import me.niteshh.redcake.command.CommandHandler;
import me.niteshh.redcake.resp.RespParser;
import me.niteshh.redcake.resp.RespValue;
import me.niteshh.redcake.resp.RespWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.List;

@Component
@AllArgsConstructor
public class ClientHandler {

    @Autowired
    private final RespParser parser;

    @Autowired
    private final CommandHandler commandHandler;

    @Autowired
    private final RespWriter respWriter;

    public void handleClient(Socket clientSocket) {
        // TODO: Implement client handling logic

        try(clientSocket){

//            byte[] buffer = new byte[1024];
//
//            int bytesRead = clientSocket.getInputStream().read(buffer);
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

            BufferedInputStream input = new BufferedInputStream(clientSocket.getInputStream());
            OutputStream outputStream = clientSocket.getOutputStream();

            while (true) {
                List<String> command = parser.parseCommand(input);

                if (command == null) {
                    System.out.println("Client disconnected: " + clientSocket.getRemoteSocketAddress());
                    break;
                }

                System.out.println("Received command: " + command);

//                RespValue response = commandHandler.handle(command);  // @handle() processes the command and returns a response in RESP format.
//                outputStream.write(response.getBytes(StandardCharsets.UTF_8)); // @write() sends the response back to the client over the TCP connection. and the response is converted to bytes using UTF-8 encoding before sending.

                RespValue response = commandHandler.handle(command);
                respWriter.write(response, outputStream);
                outputStream.flush(); // @flush() ensures that any buffered data is sent immediately, rather than waiting for the buffer to fill up.
            }

        }catch (Exception e) {
            System.out.println("Error handling client: " + e.getMessage());
        }
    }
}
