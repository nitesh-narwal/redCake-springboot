package me.niteshh.redcake.replication.replica;

import me.niteshh.redcake.command.CommandHandler;
import me.niteshh.redcake.config.RedCakeAuthConfig;
import me.niteshh.redcake.replication.ReplicationConfig;
import me.niteshh.redcake.replication.RespCommandCodec;
import me.niteshh.redcake.resp.RespParser;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.List;

@Component
public class ReplicaSyncManager {

    private final PrimaryConnection primaryConnection;
    private final ReplicationConfig replicationConfig;
    private final CommandHandler commandHandler;
    private final RedCakeAuthConfig authConfig;
    private final RespParser parser = new RespParser();

    private volatile boolean running;
    private Thread syncThread;

    public ReplicaSyncManager(
            PrimaryConnection primaryConnection,
            ReplicationConfig replicationConfig,
            CommandHandler commandHandler,
            RedCakeAuthConfig authConfig
    ) {
        this.primaryConnection = primaryConnection;
        this.replicationConfig = replicationConfig;
        this.commandHandler = commandHandler;
        this.authConfig = authConfig;
    }

    public boolean isConnected() {
        return primaryConnection.isConnected();
    }

    public synchronized void start() {
        if (running || replicationConfig.getPrimaryHost() == null) {
            return;
        }

        running = true;
        syncThread = Thread.ofVirtual()
                .name("RedCakeReplicaSync")
                .start(this::runSyncLoop);
    }

    private void runSyncLoop() {
        while (running) {
            try {
                primaryConnection.connect(
                        replicationConfig.getPrimaryHost(),
                        replicationConfig.getPrimaryPort()
                );

                Socket socket = primaryConnection.getSocket();
                OutputStream output = socket.getOutputStream();
                BufferedInputStream input =
                        new BufferedInputStream(socket.getInputStream());

                if (authConfig.isEnabled()) {
                    output.write(
                            RespCommandCodec.encode(
                                    List.of("AUTH", authConfig.apiKey())
                            )
                    );
                    output.flush();
                    readSimpleString(input, "OK");
                }

                output.write(
                        RespCommandCodec.encode(
                                List.of("REPLICAHELLO")
                        )
                );
                output.flush();
                readHandshakeResponse(input);

                while (running && primaryConnection.isConnected()) {
                    List<String> command = parser.parseCommand(input);
                    if (command == null) {
                        break;
                    }
                    commandHandler.handle(command);
                }
            } catch (Exception e) {
                if (running) {
                    System.err.println(
                            "Replica connection lost: " + e.getMessage()
                    );
                }
            } finally {
                primaryConnection.close();
            }

            if (running) {
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void readSimpleString(
            BufferedInputStream input,
            String expected
    ) throws IOException {
        String response = readLine(input);
        if (!("+" + expected).equals(response)) {
            throw new IOException("Primary authentication failed");
        }
    }

    private String readLine(
            BufferedInputStream input
    ) throws IOException {
        StringBuilder value = new StringBuilder();
        int current;
        while ((current = input.read()) != -1) {
            if (current == '\r') {
                if (input.read() != '\n') {
                    throw new IOException("Invalid primary response");
                }
                return value.toString();
            }
            value.append((char) current);
            if (value.length() > 256) {
                throw new IOException("Primary response is too long");
            }
        }
        throw new IOException("Primary closed during authentication");
    }

    private void readHandshakeResponse(
            BufferedInputStream input
    ) throws IOException {
        List<String> response = parser.parseCommand(input);
        if (response == null
                || response.size() != 2
                || !"REPLICAHELLO".equals(response.get(0))
                || !"OK".equals(response.get(1))) {
            throw new IOException("Invalid primary handshake response");
        }
    }

    public synchronized void stop() {
        running = false;
        primaryConnection.close();

        if (syncThread != null) {
            syncThread.interrupt();
            syncThread = null;
        }
    }
}
