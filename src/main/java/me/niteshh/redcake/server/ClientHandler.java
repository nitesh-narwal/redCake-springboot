package me.niteshh.redcake.server;

import me.niteshh.redcake.command.CommandHandler;
import me.niteshh.redcake.config.RedCakeAuthConfig;
import me.niteshh.redcake.replication.ReplicationManager;
import me.niteshh.redcake.resp.ErrorValue;
import me.niteshh.redcake.resp.RespParser;
import me.niteshh.redcake.resp.RespValue;
import me.niteshh.redcake.resp.RespWriter;
import me.niteshh.redcake.resp.SimpleString;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.List;

@Component
public class ClientHandler {

    private final RespParser parser;
    private final CommandHandler commandHandler;
    private final RespWriter respWriter;
    private final ReplicationManager replicationManager;
    private final RedCakeAuthConfig authConfig;

    public ClientHandler(
            RespParser parser,
            CommandHandler commandHandler,
            RespWriter respWriter,
            ReplicationManager replicationManager,
            RedCakeAuthConfig authConfig
    ) {
        this.parser = parser;
        this.commandHandler = commandHandler;
        this.respWriter = respWriter;
        this.replicationManager = replicationManager;
        this.authConfig = authConfig;
    }

    public void handleClient(Socket clientSocket) {
        try (clientSocket) {
            BufferedInputStream input =
                    new BufferedInputStream(clientSocket.getInputStream());
            OutputStream output = clientSocket.getOutputStream();
            boolean authenticated = !authConfig.isEnabled();

            while (true) {
                List<String> command = parser.parseCommand(input);

                if (command == null) {
                    return;
                }

                if (isAuthCommand(command)) {
                    boolean valid = command.size() == 2
                            && authConfig.matches(command.get(1));
                    authenticated = valid;
                    respWriter.write(
                            valid
                                    ? new SimpleString("OK")
                                    : new ErrorValue("invalid API key"),
                            output
                    );
                    output.flush();
                    continue;
                }

                if (isReplicaHandshake(command)) {
                    if (!authenticated) {
                        respWriter.write(
                                new ErrorValue("NOAUTH authentication required"),
                                output
                        );
                        output.flush();
                        return;
                    }
                    replicationManager.registerReplicaAndSendSnapshot(
                            clientSocket
                    );
                    continue;
                }

                if (!authenticated) {
                    respWriter.write(
                            new ErrorValue(
                                    "NOAUTH authentication required"
                            ),
                            output
                    );
                    output.flush();
                    continue;
                }

                RespValue response;

                if (replicationManager.isReplica()
                        && commandHandler.isWriteCommand(command)) {
                    response = new ErrorValue(
                            "READONLY replica does not accept writes"
                    );
                } else if (replicationManager.isPrimary()
                        && commandHandler.isWriteCommand(command)) {
                    replicationManager.lockPrimaryWrites();
                    try {
                        response = commandHandler.handle(command);
                        if (!(response instanceof ErrorValue)) {
                            replicationManager.replicate(command);
                        }
                    } finally {
                        replicationManager.unlockPrimaryWrites();
                    }
                } else {
                    response = commandHandler.handle(command);
                }

                respWriter.write(response, output);
                output.flush();
            }
        } catch (Exception e) {
            if (!(e instanceof java.net.SocketException)) {
                System.err.println(
                        "Error handling client: " + e.getMessage()
                );
            }
        }
    }

    private boolean isReplicaHandshake(List<String> command) {
        return command.size() == 1
                && "REPLICAHELLO".equalsIgnoreCase(command.get(0));
    }

    private boolean isAuthCommand(List<String> command) {
        return !command.isEmpty()
                && "AUTH".equalsIgnoreCase(command.get(0));
    }
}
