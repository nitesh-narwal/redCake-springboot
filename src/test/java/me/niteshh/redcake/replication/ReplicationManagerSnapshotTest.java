package me.niteshh.redcake.replication;

import me.niteshh.redcake.replication.primary.ReplicaManager;
import me.niteshh.redcake.replication.replica.PrimaryConnection;
import me.niteshh.redcake.replication.replica.ReplicaSyncManager;
import me.niteshh.redcake.resp.RespParser;
import me.niteshh.redcake.store.KeyValueStore;
import me.niteshh.redcake.store.SnapshotEntry;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.net.Socket;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;

class ReplicationManagerSnapshotTest {

    @Test
    void streamsSnapshotBeforeLiveReplication()
            throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        KeyValueStore store = mock(KeyValueStore.class);
        doAnswer(invocation -> {
            Consumer<SnapshotEntry> consumer = invocation.getArgument(0);
            consumer.accept(
                    new SnapshotEntry(
                            "existing",
                            "snapshot-value",
                            null
                    )
                );
            return null;
        }).when(store).forEachSnapshot(any());

        ReplicaManager replicas = new ReplicaManager();
        ReplicationManager manager = new ReplicationManager(
                new ReplicationConfig(
                        ReplicationRole.PRIMARY,
                        null,
                        6379
                ),
                replicas,
                mock(ReplicaSyncManager.class),
                store
        );

        manager.registerReplica(
                new TestSocket(output), "?", -1, 0
        );

        manager.replicate(
                List.of("SET", "live", "live-value")
        );
        assertTrue(
                replicas.getReplicas().getFirst().awaitIdle(2_000),
                "live write was not flushed"
        );

        List<List<String>> stream = parseCommands(output.toByteArray());
        assertEquals("FULLRESYNC", stream.get(0).get(1));
        assertEquals(
                List.of(
                        List.of("SET", "existing", "snapshot-value"),
                        List.of("REPLICAHELLO", "SYNCED"),
                        List.of("SET", "live", "live-value")
                ),
                stream.subList(1, stream.size())
        );
    }

    private List<List<String>> parseCommands(byte[] bytes)
            throws Exception {
        RespParser parser = new RespParser();
        BufferedInputStream input = new BufferedInputStream(
                new ByteArrayInputStream(bytes)
        );
        List<List<String>> commands = new java.util.ArrayList<>();
        List<String> command;

        while ((command = parser.parseCommand(input)) != null) {
            commands.add(command);
        }

        return commands;
    }

    private static final class TestSocket extends Socket {

        private final ByteArrayOutputStream output;

        private TestSocket(ByteArrayOutputStream output) {
            this.output = output;
        }

        @Override
        public ByteArrayOutputStream getOutputStream() {
            return output;
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public boolean isClosed() {
            return false;
        }
    }

}
