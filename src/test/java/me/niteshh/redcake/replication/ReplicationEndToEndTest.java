package me.niteshh.redcake.replication;

import me.niteshh.redcake.command.CommandHandler;
import me.niteshh.redcake.config.RedCakeAuthConfig;
import me.niteshh.redcake.config.RedCakeServerConfig;
import me.niteshh.redcake.persistence.AppendOnlyLog;
import me.niteshh.redcake.replication.primary.ReplicaManager;
import me.niteshh.redcake.replication.replica.PrimaryConnection;
import me.niteshh.redcake.replication.replica.ReplicaSyncManager;
import me.niteshh.redcake.resp.RespParser;
import me.niteshh.redcake.resp.RespWriter;
import me.niteshh.redcake.server.AuthFailureTracker;
import me.niteshh.redcake.server.ClientHandler;
import me.niteshh.redcake.server.RedCakeServer;
import me.niteshh.redcake.stats.ReplicationStats;
import me.niteshh.redcake.stats.ServerStats;
import me.niteshh.redcake.support.RespClient;
import me.niteshh.redcake.support.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real primary and replica over real TCP sockets inside one JVM. */
class ReplicationEndToEndTest {

    private TestSupport.Stack primary;
    private TestSupport.Stack replica;
    private RedCakeServer server;
    private ReplicaSyncManager sync;
    private ReplicaManager replicaManager;
    private ClientHandler primaryHandler;
    private RedCakeAuthConfig primaryAuth;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        primary = TestSupport.newStack();
        replica = TestSupport.newStack();
        port = freePort();
    }

    @AfterEach
    void tearDown() {
        if (sync != null) {
            sync.stop();
        }
        if (server != null) {
            server.stop();
        }
        primary.stop();
        replica.stop();
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** Builds a primary with a brand-new replication history (new replid) on the shared store. */
    private void startPrimary(String apiKey) {
        primaryAuth = new RedCakeAuthConfig(apiKey);
        replicaManager = new ReplicaManager();
        ReplicationManager replication = new ReplicationManager(
                new ReplicationConfig(ReplicationRole.PRIMARY, null, port),
                replicaManager, mock(ReplicaSyncManager.class), primary.store());
        CommandHandler commands = TestSupport.allCommands(primary.store(), ReplicationRole.PRIMARY);
        primaryHandler = new ClientHandler(new RespParser(), commands, new RespWriter(), replication,
                primaryAuth);
        startServer(new ServerStats());
    }

    /** Re-opens the listener with the SAME handler/manager, i.e. the same replication history. */
    private void startServer(ServerStats stats) {
        server = new RedCakeServer(primaryHandler,
                new RedCakeServerConfig(port, "127.0.0.1"), primaryAuth, stats);
        server.start();
    }

    private void startReplica(String apiKey) throws Exception {
        sync = new ReplicaSyncManager(
                new PrimaryConnection(),
                new ReplicationConfig(ReplicationRole.REPLICA, "127.0.0.1", port),
                TestSupport.allCommands(replica.store(), ReplicationRole.REPLICA),
                new RedCakeAuthConfig(apiKey),
                replica.store(),
                new RedCakeServerConfig(freePort(), "127.0.0.1"),
                new ReplicationStats());
        sync.start();
    }

    private RespClient client() throws Exception {
        return new RespClient("127.0.0.1", port);
    }

    private static List<String> sorted(List<String> keys) {
        List<String> copy = new ArrayList<>(keys);
        java.util.Collections.sort(copy);
        return copy;
    }

    @Test
    void snapshotThenLiveWritesConvergeAndStaleReplicaDataIsDiscarded() throws Exception {
        startPrimary(null);
        primary.store().set("existing", "v1");
        primary.store().set("with-ttl", "t", System.currentTimeMillis() + 60_000);
        primary.store().set("counter", "100");
        replica.store().set("stale", "must-disappear"); // regression B3

        startReplica(null);
        assertTrue(TestSupport.await(5_000, () -> "v1".equals(replica.store().get("existing"))),
                "snapshot did not arrive");
        assertNull(replica.store().get("stale"), "full sync must replace the replica's old data");
        assertEquals(1, replicaManager.replicaCount());

        try (RespClient c = client()) {
            assertEquals("+OK", c.call("SET", "live", "1"));
            for (int i = 0; i < 100; i++) {
                c.call("INCR", "counter");
            }
            assertEquals(":1", c.call("DEL", "existing"));
            assertEquals("+OK", c.call("SET", "temp", "x", "EX", "100"));
            assertEquals(":1", c.call("EXPIRE", "live", "100"));
        }

        assertTrue(TestSupport.await(5_000, () -> "200".equals(replica.store().get("counter"))));
        assertTrue(TestSupport.await(5_000, () -> replica.store().get("existing") == null));
        assertEquals("1", replica.store().get("live"));
        assertEquals(sorted(primary.store().keys("*")), sorted(replica.store().keys("*")));

        // Absolute deadlines: replica TTL must match the primary's, not drift by latency.
        long primaryTtl = primary.store().pttl("temp");
        long replicaTtl = replica.store().pttl("temp");
        assertTrue(Math.abs(primaryTtl - replicaTtl) < 500, primaryTtl + " vs " + replicaTtl);
    }

    @Test
    void replicaAuthenticatesWithTheSharedKey() throws Exception {
        startPrimary("s3cret");
        primary.store().set("k", "v");

        startReplica("s3cret");
        assertTrue(TestSupport.await(5_000, () -> "v".equals(replica.store().get("k"))));
    }

    @Test
    void replicaWithWrongKeyNeverReceivesData() throws Exception {
        startPrimary("s3cret");
        primary.store().set("k", "v");

        startReplica("wrong");
        Thread.sleep(1_500);
        assertNull(replica.store().get("k"));
        assertEquals(0, replicaManager.replicaCount());
    }

    /** A restarted primary has a new replid, so the replica must do a FULL resync and drop stale keys. */
    @Test
    void replicaFullyResynchronizesWhenThePrimaryHistoryChanges() throws Exception {
        startPrimary(null);
        primary.store().set("before", "1");
        startReplica(null);
        assertTrue(TestSupport.await(5_000, () -> "1".equals(replica.store().get("before"))));

        server.stop();
        primary.store().set("while-down", "2");
        primary.store().delete("before");
        startPrimary(null); // new ReplicationManager => new replid

        assertTrue(TestSupport.await(10_000, () -> "2".equals(replica.store().get("while-down"))),
                "replica did not reconnect");
        assertNull(replica.store().get("before"), "key deleted during the outage must be gone after resync");
    }

    /**
     * Same history + the missed bytes still in the backlog: PARTIAL resync. Proof that no full sync
     * happened: a marker key that exists only on the replica survives (a full sync would clear it).
     */
    @Test
    void replicaResumesWithAPartialResyncAfterABriefDisconnect() throws Exception {
        startPrimary(null);
        startReplica(null);
        try (RespClient c = client()) {
            assertEquals("+OK", c.call("SET", "first", "1"));
        }
        assertTrue(TestSupport.await(5_000, () -> "1".equals(replica.store().get("first"))));
        replica.store().set("replica-only-marker", "x");

        server.stop();
        startServer(new ServerStats()); // same handler => same manager, backlog and replid
        try (RespClient c = client()) {
            assertEquals("+OK", c.call("SET", "missed", "2")); // replica is not attached right now
            assertEquals(":1", c.call("INCR", "n"));
        }

        assertTrue(TestSupport.await(10_000, () -> "2".equals(replica.store().get("missed"))),
                "missed writes must arrive after the replica reconnects");
        assertEquals("1", replica.store().get("n"));
        assertEquals("x", replica.store().get("replica-only-marker"),
                "a partial resync must keep the replica's data");
    }

    /** WAIT returns once the replica has acknowledged the write. */
    @Test
    void waitReturnsWhenReplicasAcknowledge() throws Exception {
        startPrimary(null);
        startReplica(null);
        assertTrue(TestSupport.await(5_000, () -> replicaManager.replicaCount() == 1));

        try (RespClient c = client()) {
            assertEquals("+OK", c.call("SET", "w", "1"));
            assertEquals(":1", c.call("WAIT", "1", "3000"));
            assertEquals("\"1\"", c.call("GET", "w")); // sanity
        }
        assertEquals("1", replica.store().get("w"), "WAIT 1 must imply the replica applied the write");
    }

    /** REPLICAOF NO ONE promotes a replica; REPLICAOF host port turns a node into a replica at runtime. */
    @Test
    void replicaofCommandSwitchesRolesAtRuntime() throws Exception {
        startPrimary(null);
        primary.store().set("a", "1");

        // A second node, started as a standalone primary, is then pointed at the first one.
        TestSupport.Stack second = TestSupport.newStack();
        int secondPort = freePort();
        ReplicationConfig secondConfig = new ReplicationConfig(ReplicationRole.PRIMARY, null, secondPort);
        ReplicationStats secondStats = new ReplicationStats();
        ReplicaSyncManager secondSync = new ReplicaSyncManager(
                new PrimaryConnection(), secondConfig,
                TestSupport.allCommands(second.store(), ReplicationRole.REPLICA),
                new RedCakeAuthConfig(null), second.store(),
                new RedCakeServerConfig(secondPort, "127.0.0.1"), secondStats);
        ReplicationManager secondReplication = new ReplicationManager(
                secondConfig, new ReplicaManager(), secondSync, second.store(), secondStats);
        ClientHandler secondHandler = new ClientHandler(new RespParser(),
                TestSupport.allCommands(second.store(), ReplicationRole.PRIMARY),
                new RespWriter(), secondReplication, new RedCakeAuthConfig(null));
        RedCakeServer secondServer = new RedCakeServer(secondHandler,
                new RedCakeServerConfig(secondPort, "127.0.0.1"), new RedCakeAuthConfig(null), new ServerStats());
        secondServer.start();
        try (RespClient c = new RespClient("127.0.0.1", secondPort)) {
            assertEquals("+OK", c.call("SET", "local", "x"));
            assertEquals("+OK", c.call("REPLICAOF", "127.0.0.1", Integer.toString(port)));
            assertTrue(TestSupport.await(10_000, () -> "1".equals(second.store().get("a"))),
                    "after REPLICAOF the node must sync from the new primary");
            assertNull(second.store().get("local"), "its own data is replaced by the primary's");
            assertTrue(c.call("SET", "denied", "1").startsWith("-READONLY"));

            assertEquals("+OK", c.call("REPLICAOF", "NO", "ONE"));
            assertEquals("+OK", c.call("SET", "now-writable", "1"));
        } finally {
            secondReplication.shutdown();
            secondServer.stop();
            second.stop();
        }
    }

    @Test
    void disconnectedReplicaIsRemovedFromThePrimary() throws Exception {
        startPrimary(null);
        startReplica(null);
        assertTrue(TestSupport.await(5_000, () -> replicaManager.replicaCount() == 1));

        sync.stop();
        assertTrue(TestSupport.await(5_000, () -> replicaManager.replicaCount() == 0),
                "primary must notice the closed replica link");
    }

    /**
     * The scenario the point-in-time snapshot exists for: several replicas join while clients keep writing
     * (including non-idempotent INCRs). After the writers stop, every replica must equal the primary exactly.
     */
    @Test
    void threeReplicasConvergeWhileClientsWriteDuringTheirFullSync() throws Exception {
        startPrimary(null);
        for (int i = 0; i < 3_000; i++) {
            primary.store().set("seed" + i, "v" + i);
        }
        primary.store().set("counter", "0");

        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicLong increments = new java.util.concurrent.atomic.AtomicLong();
        List<Thread> writers = new ArrayList<>();
        for (int w = 0; w < 4; w++) {
            final int id = w;
            writers.add(Thread.ofPlatform().start(() -> {
                try (RespClient c = client()) {
                    int n = 0;
                    while (!stop.get()) {
                        c.call("INCR", "counter");
                        increments.incrementAndGet();
                        c.call("SET", "w" + id + "-" + (n++ % 50), "x" + n);
                        c.call("HSET", "hash" + id, "f" + (n % 20), Integer.toString(n));
                        c.call("RPUSH", "list" + id, Integer.toString(n));
                        if (n % 10 == 0) {
                            c.call("LPOP", "list" + id);
                        }
                    }
                } catch (Exception ignored) {
                    // the test ends the writers by flag; a failure shows up as divergence below
                }
            }));
        }

        List<TestSupport.Stack> replicas = new ArrayList<>();
        List<ReplicaSyncManager> syncs = new ArrayList<>();
        try {
            Thread.sleep(200); // writers are already running when the replicas connect
            for (int r = 0; r < 3; r++) {
                TestSupport.Stack stack = TestSupport.newStack();
                replicas.add(stack);
                ReplicaSyncManager sync = new ReplicaSyncManager(
                        new PrimaryConnection(),
                        new ReplicationConfig(ReplicationRole.REPLICA, "127.0.0.1", port),
                        TestSupport.allCommands(stack.store(), ReplicationRole.REPLICA),
                        new RedCakeAuthConfig(null), stack.store(),
                        new RedCakeServerConfig(freePort(), "127.0.0.1"), new ReplicationStats());
                syncs.add(sync);
                sync.start();
                Thread.sleep(150);
            }
            Thread.sleep(1_500); // keep writing while they sync and stream
        } finally {
            stop.set(true);
            for (Thread writer : writers) {
                writer.join(5_000);
            }
        }

        try {
            assertTrue(TestSupport.await(10_000, () -> replicaManager.replicaCount() == 3), "all replicas attached");
            String expectedCounter = primary.store().get("counter");
            assertEquals(Long.toString(increments.get()), expectedCounter, "no INCR was lost on the primary");

            for (TestSupport.Stack replicaStack : replicas) {
                assertTrue(TestSupport.await(15_000, () -> identical(primary, replicaStack)),
                        "a replica diverged from the primary: " + diff(primary, replicaStack));
            }
        } finally {
            syncs.forEach(ReplicaSyncManager::stop);
            replicas.forEach(TestSupport.Stack::stop);
        }
    }

    /** Same key set, same values for every type we wrote. */
    private static boolean identical(TestSupport.Stack a, TestSupport.Stack b) {
        return diff(a, b).isEmpty();
    }

    private static String diff(TestSupport.Stack a, TestSupport.Stack b) {
        List<String> keysA = sorted(a.store().keys("*"));
        List<String> keysB = sorted(b.store().keys("*"));
        if (!keysA.equals(keysB)) {
            return "key sets differ: " + keysA.size() + " vs " + keysB.size();
        }
        for (String key : keysA) {
            Object left = describe(a, key);
            Object right = describe(b, key);
            if (!left.equals(right)) {
                return "value of " + key + " differs: " + left + " vs " + right;
            }
        }
        return "";
    }

    private static Object describe(TestSupport.Stack stack, String key) {
        var type = stack.store().type(key);
        if (type == me.niteshh.redcake.store.ValueType.STRING) {
            return stack.store().get(key);
        }
        List<Object> copy = new ArrayList<>();
        stack.store().forEachSnapshot(entry -> {
            if (entry.key().equals(key)) {
                copy.add(entry.value());
            }
        });
        return copy.isEmpty() ? "<gone>" : copy.getFirst();
    }
}
