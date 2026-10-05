package me.niteshh.redcake.replication;

import me.niteshh.redcake.command.CommandHandler;
import me.niteshh.redcake.replication.primary.ReplicaConnection;
import me.niteshh.redcake.replication.primary.ReplicaManager;
import me.niteshh.redcake.replication.replica.PrimaryConnection;
import me.niteshh.redcake.replication.replica.ReplicaSyncManager;
import me.niteshh.redcake.resp.RespParser;
import me.niteshh.redcake.support.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Unit-level guarantees of the primary side: consistent snapshot and non-blocking writers. */
class ReplicationBehaviourTest {

    private TestSupport.Stack primary;
    private TestSupport.Stack replica;

    @BeforeEach
    void setUp() {
        primary = TestSupport.newStack();
        replica = TestSupport.newStack();
    }

    @AfterEach
    void tearDown() {
        primary.stop();
        replica.stop();
    }

    private ReplicationManager manager() {
        return new ReplicationManager(
                new ReplicationConfig(ReplicationRole.PRIMARY, null, 6379),
                new ReplicaManager(),
                mock(ReplicaSyncManager.class),
                primary.store());
    }

    /** Executes a write exactly like ClientHandler does: under the lock, then replicate. */
    private void primaryWrite(ReplicationManager manager, CommandHandler handler, String... command) {
        manager.lockPrimaryWrites();
        try {
            List<String> normalized = handler.normalize(List.of(command), System.currentTimeMillis());
            handler.handle(normalized);
            manager.replicate(normalized);
        } finally {
            manager.unlockPrimaryWrites();
        }
    }

    private static List<List<String>> parse(byte[] bytes) throws Exception {
        RespParser parser = new RespParser();
        BufferedInputStream in = new BufferedInputStream(new ByteArrayInputStream(bytes));
        List<List<String>> commands = new ArrayList<>();
        List<String> command;
        while ((command = parser.parseCommand(in)) != null) {
            commands.add(command);
        }
        return commands;
    }

    /**
     * Regression B2: with a counter, a write that happens after the snapshot
     * was taken must reach the replica exactly once (not both inside the
     * snapshot and again through the queue).
     */
    @Test
    void replicaConvergesWhenWritesFollowTheSnapshot() throws Exception {
        CommandHandler handler = TestSupport.allCommands(primary.store(), ReplicationRole.PRIMARY);
        ReplicationManager manager = manager();
        ByteArrayOutputStream wire = new ByteArrayOutputStream();

        handler.handle(List.of("SET", "counter", "10"));
        handler.handle(List.of("SET", "ttl-key", "x", "PXAT", Long.toString(System.currentTimeMillis() + 60_000)));

        ReplicaConnection connection = manager.registerReplica(new WireSocket(wire), "?", -1, 0);
        for (int i = 0; i < 5; i++) {
            primaryWrite(manager, handler, "INCR", "counter");
        }
        primaryWrite(manager, handler, "DEL", "ttl-key");
        assertTrue(connection.awaitIdle(2_000));

        CommandHandler replicaHandler = TestSupport.allCommands(replica.store(), ReplicationRole.REPLICA);
        List<List<String>> stream = parse(wire.toByteArray());
        assertEquals("REPLICAHELLO", stream.getFirst().getFirst());
        assertEquals("FULLRESYNC", stream.getFirst().get(1));
        for (List<String> command : stream.subList(1, stream.size())) {
            if (!command.getFirst().equals("REPLICAHELLO")) { // SYNCED marker
                replicaHandler.handle(command);
            }
        }

        assertEquals("15", primary.store().get("counter"));
        assertEquals("15", replica.store().get("counter"), "INCR must not be applied twice");
        assertNull(replica.store().get("ttl-key"));
    }

    @Test
    void snapshotCarriesAbsoluteExpiryNotRelative() throws Exception {
        CommandHandler handler = TestSupport.allCommands(primary.store(), ReplicationRole.PRIMARY);
        long deadline = System.currentTimeMillis() + 90_000;
        handler.handle(List.of("SET", "k", "v", "PXAT", Long.toString(deadline)));

        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        manager().registerReplica(new WireSocket(wire), "?", -1, 0);

        assertEquals(
                List.of("SET", "k", "v", "PXAT", Long.toString(deadline)),
                parse(wire.toByteArray()).get(1));
    }

    @Test
    void replicationOffsetCountsEveryReplicatedByte() {
        ReplicationManager manager = manager();
        manager.replicate(List.of("SET", "a", "1"));
        manager.replicate(List.of("DEL", "a"));
        long expected = RespCommandCodec.encode(List.of("SET", "a", "1")).length
                + RespCommandCodec.encode(List.of("DEL", "a")).length;
        assertEquals(expected, manager.getStats().offset());
    }

    /** Regression B1: a stuck replica socket must not stall the writers. */
    @Test
    void slowReplicaDoesNotBlockPrimaryWrites() throws Exception {
        CommandHandler handler = TestSupport.allCommands(primary.store(), ReplicationRole.PRIMARY);
        ReplicationManager manager = manager();
        StallableWire wire = new StallableWire();

        ReplicaConnection connection = manager.registerReplica(new WireSocket(wire), "?", -1, 0);
        assertTrue(connection.awaitIdle(1_000));
        wire.stall();

        long start = System.nanoTime();
        for (int i = 0; i < 200; i++) {
            primaryWrite(manager, handler, "INCR", "n");
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals("200", primary.store().get("n"));
        assertTrue(elapsedMillis < 1_000,
                "writers were blocked by the stalled replica: " + elapsedMillis + " ms");

        wire.release();
        assertTrue(connection.awaitIdle(3_000), "queued writes must be delivered once the replica recovers");
        assertEquals(200, parse(wire.toByteArray()).stream()
                .filter(c -> c.getFirst().equals("INCR")).count());
    }

    @Test
    void replicaThatFallsTooFarBehindIsDroppedInsteadOfEatingMemory() throws Exception {
        ReplicaManager replicas = new ReplicaManager();
        ReplicationManager manager = new ReplicationManager(
                new ReplicationConfig(ReplicationRole.PRIMARY, null, 6379),
                replicas, mock(ReplicaSyncManager.class), primary.store());
        StallableWire wire = new StallableWire();
        ReplicaConnection connection = manager.registerReplica(new WireSocket(wire), "?", -1, 0);
        connection.awaitIdle(1_000);
        wire.stall();

        String chunk = "x".repeat(1024 * 1024);
        for (int i = 0; i < 80 && replicas.replicaCount() > 0; i++) {
            manager.replicate(List.of("SET", "big", chunk));
        }

        assertEquals(0, replicas.replicaCount(), "a replica 64 MiB behind must be dropped");
        assertTrue(connection.isClosed());
        wire.release();
    }

    @Test
    void closedReplicaRejectsFurtherWrites() {
        ReplicaConnection connection = new ReplicaConnection("r", new ByteArrayOutputStream());
        connection.close();
        assertThrows(java.io.IOException.class, () -> connection.send(new byte[]{1}));
    }

    /** A "socket" that only exposes an output stream - all the primary needs from a replica link. */
    static final class WireSocket extends Socket {
        private final ByteArrayOutputStream wire;

        WireSocket(ByteArrayOutputStream wire) {
            this.wire = wire;
        }

        @Override
        public ByteArrayOutputStream getOutputStream() {
            return wire;
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

    /** Byte sink whose writes can be frozen to simulate a replica that stopped reading. */
    static final class StallableWire extends ByteArrayOutputStream {
        private volatile CountDownLatch gate = new CountDownLatch(0);

        void stall() {
            gate = new CountDownLatch(1);
        }

        void release() {
            gate.countDown();
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            synchronized (this) {
                super.write(bytes, offset, length);
            }
        }

        @Override
        public synchronized byte[] toByteArray() {
            return super.toByteArray();
        }
    }
}
