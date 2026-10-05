package me.niteshh.redcake.replication.replica;

import lombok.extern.slf4j.Slf4j;
import me.niteshh.redcake.command.CommandHandler;
import me.niteshh.redcake.config.RedCakeAuthConfig;
import me.niteshh.redcake.config.RedCakeServerConfig;
import me.niteshh.redcake.replication.ReplicationConfig;
import me.niteshh.redcake.replication.RespCommandCodec;
import me.niteshh.redcake.resp.ErrorValue;
import me.niteshh.redcake.resp.RespParser;
import me.niteshh.redcake.resp.RespValue;
import me.niteshh.redcake.stats.ReplicationStats;
import me.niteshh.redcake.store.KeyValueStore;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The replica's background worker: keeps this node synchronized with its primary.
 *
 * <h3>One cycle (see {@link #synchronizeOnce})</h3>
 * <ol>
 *   <li>Connect (TCP or TLS), {@code AUTH} if an API key is configured.</li>
 *   <li>{@code REPLCONF listening-port <this node's port>} and {@code REPLCONF capa psync2}.</li>
 *   <li>{@code PSYNC <replid> <offset>} - {@code ? -1} on the first connection,
 *       afterwards the history id and stream offset we last applied.</li>
 *   <li>The primary answers {@code REPLICAHELLO FULLRESYNC <replid> <offset>}
 *       (we <b>clear the local store</b>: the snapshot that follows is the whole
 *       truth, so keys deleted while we were away disappear) or
 *       {@code REPLICAHELLO CONTINUE ...} (we keep our data and only receive the
 *       missed bytes).</li>
 *   <li>Commands are applied through the normal {@link CommandHandler}. Snapshot
 *       commands do not advance the offset; {@code REPLICAHELLO SYNCED} marks the
 *       end of the sync, after which every applied command adds its byte length
 *       to the offset.</li>
 *   <li>A helper thread sends {@code REPLCONF ACK <offset>} every second, and at
 *       once when the primary asks with {@code REPLCONF GETACK} (used by {@code WAIT}).</li>
 * </ol>
 *
 * <h3>Failure handling</h3>
 * The socket has a 60 s read timeout; the primary pings every 10 s, so silence
 * means a dead primary and triggers a reconnect with exponential backoff
 * (1 s up to 30 s, with jitter). Each {@link #start()} gets a new
 * <em>generation</em> number so a thread left over from before a
 * {@code REPLICAOF} change can never act on the new connection.
 */
@Slf4j
@Component
public class ReplicaSyncManager {

    /** A healthy primary sends something at least every ~10 s (heartbeat). */
    private static final int READ_TIMEOUT_MILLIS = 60_000;
    private static final long INITIAL_BACKOFF_MILLIS = 1_000;
    private static final long MAX_BACKOFF_MILLIS = 30_000;
    private static final long ACK_INTERVAL_MILLIS = 1_000;

    private final PrimaryConnection primaryConnection;
    private final ReplicationConfig replicationConfig;
    private final CommandHandler commandHandler;
    private final RedCakeAuthConfig authConfig;
    private final KeyValueStore store;
    private final RedCakeServerConfig serverConfig;
    private final ReplicationStats stats;
    private final RespParser parser = new RespParser();

    private volatile boolean running;
    private volatile int generation;
    private Thread syncThread;

    public ReplicaSyncManager(
            PrimaryConnection primaryConnection,
            ReplicationConfig replicationConfig,
            CommandHandler commandHandler,
            RedCakeAuthConfig authConfig,
            KeyValueStore store,
            RedCakeServerConfig serverConfig,
            ReplicationStats stats
    ) {
        this.primaryConnection = primaryConnection;
        this.replicationConfig = replicationConfig;
        this.commandHandler = commandHandler;
        this.authConfig = authConfig;
        this.store = store;
        this.serverConfig = serverConfig;
        this.stats = stats;
    }

    public boolean isConnected() {
        return primaryConnection.isConnected();
    }

    /** Starts the sync thread (no-op if already running or no primary configured). */
    public synchronized void start() {
        if (running || replicationConfig.getPrimaryHost() == null) {
            return;
        }
        running = true;
        int mine = ++generation;
        syncThread = Thread.ofVirtual()
                .name("RedCakeReplicaSync")
                .start(() -> runSyncLoop(mine));
    }

    /** Stops the sync thread and closes the primary connection. */
    public synchronized void stop() {
        running = false;
        generation++; // orphan any thread that is still winding down
        primaryConnection.close();
        stats.setMasterLinkUp(false);
        if (syncThread != null) {
            syncThread.interrupt();
            syncThread = null;
        }
    }

    /** Re-reads the primary address from the config and reconnects ({@code REPLICAOF host port}). */
    public synchronized void restart() {
        stop();
        start();
    }

    private void runSyncLoop(int mine) {
        long backoff = INITIAL_BACKOFF_MILLIS;

        while (running && generation == mine) {
            try {
                synchronizeOnce(mine);
                backoff = INITIAL_BACKOFF_MILLIS; // we got far enough to be synced: reset
            } catch (Exception e) {
                if (running && generation == mine) {
                    log.warn("Replica connection lost: {}", e.getMessage());
                }
            } finally {
                if (generation == mine) { // never touch a newer thread's connection
                    stats.setMasterLinkUp(false);
                    primaryConnection.close();
                }
            }

            if (running && generation == mine) {
                try {
                    Thread.sleep(backoff + ThreadLocalRandom.current().nextLong(backoff / 2 + 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MILLIS);
            }
        }
    }

    /** One full connect -> handshake -> sync -> stream cycle; returns/throws when the link ends. */
    private void synchronizeOnce(int mine) throws IOException {
        primaryConnection.connect(
                replicationConfig.getPrimaryHost(),
                replicationConfig.getPrimaryPort()
        );

        Socket socket = primaryConnection.getSocket();
        socket.setSoTimeout(READ_TIMEOUT_MILLIS);
        OutputStream output = socket.getOutputStream();
        BufferedInputStream input = new BufferedInputStream(socket.getInputStream());

        if (authConfig.isEnabled()) {
            sendCommand(output, List.of("AUTH", authConfig.apiKey()));
            readSimpleString(input, "OK", "Primary authentication failed");
        }

        // The primary needs OUR listening port (not the ephemeral outbound one).
        sendCommand(output, List.of(
                "REPLCONF", "listening-port", String.valueOf(serverConfig.getPort())
        ));
        readSimpleString(input, "OK", "Primary rejected REPLCONF listening-port");

        sendCommand(output, List.of("REPLCONF", "capa", "psync2"));
        readSimpleString(input, "OK", "Primary rejected REPLCONF capa");

        boolean canResume = stats.isSynced();
        sendCommand(output, List.of(
                "PSYNC",
                canResume ? stats.replid() : "?",
                canResume ? Long.toString(stats.offset()) : "-1"
        ));
        List<String> hello = readHello(input);
        boolean full = "FULLRESYNC".equals(hello.get(1));

        if (full) {
            store.clear();          // the snapshot is the complete truth
            stats.setSynced(false); // a half-loaded snapshot must never be resumed from
            stats.setLoading(true); // clients get -LOADING instead of a partial dataset
        }
        stats.adopt(hello.get(2), Long.parseLong(hello.get(3)));
        log.info("{} with primary {}:{} (offset {})",
                full ? "Full resync" : "Partial resync",
                replicationConfig.getPrimaryHost(), replicationConfig.getPrimaryPort(),
                hello.get(3));

        Thread acker = Thread.ofVirtual().name("RedCakeReplicaAck").unstarted(
                () -> ackLoop(mine, output));
        boolean inSnapshot = full;
        try {
            while (running && generation == mine && primaryConnection.isConnected()) {
                List<String> command = parser.parseCommand(input);
                if (command == null) {
                    break;
                }

                if ("REPLICAHELLO".equals(command.getFirst())) {
                    if (command.size() == 2 && "SYNCED".equals(command.get(1))) {
                        inSnapshot = false;
                        stats.setLoading(false);
                        stats.setSynced(true);
                        stats.setMasterLinkUp(true);
                        acker.start();
                        sendAck(output);
                    }
                    continue;
                }

                boolean getAck = isGetAck(command);
                if (!getAck) {
                    RespValue reply = commandHandler.handle(command);
                    if (reply instanceof ErrorValue error) {
                        log.warn("Replicated command {} failed: {}", command.getFirst(), error.message());
                    }
                }
                if (!inSnapshot) {
                    stats.advance(RespCommandCodec.encode(command).length);
                }
                if (getAck) {
                    sendAck(output);
                }
            }
        } finally {
            acker.interrupt();
        }
    }

    /** Periodically tells the primary how far we got. Ends when interrupted or the socket fails. */
    private void ackLoop(int mine, OutputStream output) {
        try {
            while (running && generation == mine) {
                Thread.sleep(ACK_INTERVAL_MILLIS);
                sendAck(output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // the reader thread notices the broken socket and reconnects
        }
    }

    /** {@code REPLCONF ACK <offset>}; synchronized because the ack thread and reader both write. */
    private void sendAck(OutputStream output) throws IOException {
        synchronized (output) {
            sendCommand(output, List.of("REPLCONF", "ACK", Long.toString(stats.offset())));
        }
    }

    private static boolean isGetAck(List<String> command) {
        return command.size() == 3
                && "REPLCONF".equalsIgnoreCase(command.get(0))
                && "GETACK".equalsIgnoreCase(command.get(1));
    }

    private void sendCommand(OutputStream output, List<String> command) throws IOException {
        output.write(RespCommandCodec.encode(command));
        output.flush();
    }

    private void readSimpleString(
            BufferedInputStream input,
            String expected,
            String failureMessage
    ) throws IOException {
        String response = readLine(input);
        if (!("+" + expected).equals(response)) {
            throw new IOException(failureMessage + " (" + response + ")");
        }
    }

    private String readLine(BufferedInputStream input) throws IOException {
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
        throw new IOException("Primary closed during handshake");
    }

    /**
     * Reads {@code REPLICAHELLO FULLRESYNC|CONTINUE <replid> <offset>}; surfaces
     * a RESP error line from the primary verbatim.
     */
    private List<String> readHello(BufferedInputStream input) throws IOException {
        input.mark(1);
        int firstByte = input.read();
        input.reset();

        if (firstByte == '-') {
            throw new IOException("Primary rejected PSYNC: " + readLine(input));
        }

        List<String> hello = parser.parseCommand(input);
        if (hello == null
                || hello.size() != 4
                || !"REPLICAHELLO".equals(hello.get(0))
                || !("FULLRESYNC".equals(hello.get(1)) || "CONTINUE".equals(hello.get(1)))) {
            throw new IOException("Invalid primary handshake response");
        }
        try {
            Long.parseLong(hello.get(3));
        } catch (NumberFormatException e) {
            throw new IOException("Invalid offset in primary handshake");
        }
        return hello;
    }
}
