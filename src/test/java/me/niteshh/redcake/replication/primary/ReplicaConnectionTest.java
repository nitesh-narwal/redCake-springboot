package me.niteshh.redcake.replication.primary;

import me.niteshh.redcake.replication.RespCommandCodec;
import me.niteshh.redcake.resp.RespParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplicaConnectionTest {

    @Test
    void queuesWritesDuringSnapshotAndFlushesThemAfterSnapshot()
            throws Exception {
        BlockingOutputStream output = new BlockingOutputStream();
        ReplicaConnection replica =
                new ReplicaConnection("replica-1", output);

        byte[] snapshot = RespCommandCodec.encode(
                List.of("SET", "from-snapshot", "snapshot-value")
        );
        byte[] liveWrite = RespCommandCodec.encode(
                List.of("SET", "during-snapshot", "live-value")
        );

        Thread snapshotThread = Thread.ofPlatform().start(
                () -> {
                    try {
                        replica.writeSnapshotCommand(snapshot);
                        replica.finishSnapshot();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
        );

        assertTrue(
                output.firstWriteStarted.await(2, TimeUnit.SECONDS),
                "snapshot write did not start"
        );

        replica.send(liveWrite);
        output.allowWrites.countDown();
        snapshotThread.join(2_000);

        assertEquals(
                List.of(
                        List.of("SET", "from-snapshot", "snapshot-value"),
                        List.of("SET", "during-snapshot", "live-value")
                ),
                parseCommands(output.toByteArray())
        );
        assertEquals(2, parseCommands(output.toByteArray()).size());
    }

    @Test
    void sendsWritesNormallyAfterSnapshotBarrierOpens()
            throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ReplicaConnection replica =
                new ReplicaConnection(
                        "replica-2",
                        output
                );

        replica.writeSnapshotCommand(
                RespCommandCodec.encode(
                        List.of("SET", "key", "snapshot")
                )
        );
        replica.finishSnapshot();
        replica.send(
                RespCommandCodec.encode(
                        List.of("SET", "key", "live")
                )
        );

        assertEquals(
                List.of(
                        List.of("SET", "key", "snapshot"),
                        List.of("SET", "key", "live")
                ),
                parseCommands(output.toByteArray())
        );
    }

    private List<List<String>> parseCommands(byte[] bytes)
            throws IOException {
        BufferedInputStream input = new BufferedInputStream(
                new ByteArrayInputStream(bytes)
        );
        RespParser parser = new RespParser();
        List<List<String>> commands = new java.util.ArrayList<>();
        List<String> command;

        while ((command = parser.parseCommand(input)) != null) {
            commands.add(command);
        }

        return commands;
    }

    private static final class BlockingOutputStream
            extends ByteArrayOutputStream {

        private final CountDownLatch firstWriteStarted =
                new CountDownLatch(1);
        private final CountDownLatch allowWrites =
                new CountDownLatch(1);
        private boolean firstWrite = true;

        @Override
        public synchronized void write(
                byte[] bytes,
                int offset,
                int length
        ) {
            if (firstWrite) {
                firstWrite = false;
                firstWriteStarted.countDown();
                try {
                    if (!allowWrites.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError(
                                "timed out waiting to release snapshot"
                        );
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
            super.write(bytes, offset, length);
        }
    }
}
