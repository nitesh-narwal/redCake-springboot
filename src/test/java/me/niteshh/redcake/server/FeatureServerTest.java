package me.niteshh.redcake.server;

import me.niteshh.redcake.replication.ReplicationRole;
import me.niteshh.redcake.support.RespClient;
import me.niteshh.redcake.support.TestServer;
import me.niteshh.redcake.support.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Black-box tests of the "extra" server features through a real socket:
 * transactions, Pub/Sub, observability commands, binary safety, expiry
 * propagation, the replica LOADING state.
 */
class FeatureServerTest {

    private TestServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private TestServer start() throws Exception {
        server = new TestServer().start();
        return server;
    }

    // ------------------------------------------------------- transactions

    @Test
    void multiExecRunsQueuedCommandsAndReturnsTheirRepliesTogether() throws Exception {
        start();
        try (RespClient c = server.client()) {
            assertEquals("+OK", c.call("MULTI"));
            assertEquals("+QUEUED", c.call("SET", "a", "1"));
            assertEquals("+QUEUED", c.call("INCR", "a"));
            assertEquals("+QUEUED", c.call("GET", "a"));
            assertEquals("[+OK, :2, \"2\"]", c.call("EXEC"));
            assertEquals("\"2\"", c.call("GET", "a"));
        }
    }

    @Test
    void queuedCommandsDoNotRunUntilExecAndDiscardDropsThem() throws Exception {
        start();
        try (RespClient c = server.client()) {
            c.call("MULTI");
            c.call("SET", "ghost", "x");
            assertEquals("(nil)", server.client().call("GET", "ghost"), "not visible before EXEC");
            assertEquals("+OK", c.call("DISCARD"));
            assertEquals("(nil)", c.call("GET", "ghost"));
            assertEquals("-ERR EXEC without MULTI", c.call("EXEC"));
            assertEquals("-ERR DISCARD without MULTI", c.call("DISCARD"));
        }
    }

    @Test
    void aTransactionWithAnUnknownCommandIsAborted() throws Exception {
        start();
        try (RespClient c = server.client()) {
            c.call("MULTI");
            assertEquals("+QUEUED", c.call("SET", "x", "1"));
            assertTrue(c.call("NOSUCH").startsWith("-ERR unknown command"));
            assertEquals("-EXECABORT Transaction discarded because of previous errors.", c.call("EXEC"));
            assertEquals("(nil)", c.call("GET", "x"), "nothing may have run");
        }
    }

    @Test
    void multiCannotBeNestedAndWatchIsRefusedInsideIt() throws Exception {
        start();
        try (RespClient c = server.client()) {
            c.call("MULTI");
            assertEquals("-ERR MULTI calls can not be nested", c.call("MULTI"));
            assertEquals("-ERR WATCH inside MULTI is not allowed", c.call("WATCH", "k"));
        }
    }

    @Test
    void watchAbortsTheTransactionWhenAnotherClientChangedTheKey() throws Exception {
        start();
        try (RespClient a = server.client(); RespClient b = server.client()) {
            a.call("SET", "balance", "100");
            assertEquals("+OK", a.call("WATCH", "balance"));
            assertEquals("+OK", b.call("SET", "balance", "50")); // concurrent write
            a.call("MULTI");
            a.call("SET", "balance", "999");
            assertEquals("(nil)", a.read0("EXEC"), "EXEC must return a null array when a watched key changed");
            assertEquals("\"50\"", a.call("GET", "balance"));
        }
    }

    @Test
    void watchLetsTheTransactionRunWhenNothingChanged() throws Exception {
        start();
        try (RespClient a = server.client()) {
            a.call("SET", "k", "1");
            a.call("WATCH", "k");
            a.call("MULTI");
            a.call("INCR", "k");
            assertEquals("[:2]", a.call("EXEC"));
            // watches are cleared by EXEC: a later change must not matter
            a.call("WATCH", "k");
            assertEquals("+OK", a.call("UNWATCH"));
        }
    }

    // ------------------------------------------------------------ pub/sub

    @Test
    void publishReachesChannelAndPatternSubscribers() throws Exception {
        start();
        try (RespClient sub = server.client(); RespClient pub = server.client()) {
            assertEquals("[\"subscribe\", \"news\", :1]", sub.call("SUBSCRIBE", "news"));
            assertEquals("[\"psubscribe\", \"n*\", :2]", sub.call("PSUBSCRIBE", "n*"));

            assertEquals(":2", pub.call("PUBLISH", "news", "hello")); // one direct + one pattern
            assertEquals("[\"message\", \"news\", \"hello\"]", sub.read());
            assertEquals("[\"pmessage\", \"n*\", \"news\", \"hello\"]", sub.read());

            assertEquals(":0", pub.call("PUBLISH", "other", "nobody"));
            assertEquals("[\"news\", :1]", pub.call("PUBSUB", "NUMSUB", "news"));
            assertEquals("[\"news\"]", pub.call("PUBSUB", "CHANNELS"));
            assertEquals(":1", pub.call("PUBSUB", "NUMPAT"));
        }
    }

    @Test
    void subscribedConnectionsAreRestrictedAndCanUnsubscribe() throws Exception {
        start();
        try (RespClient sub = server.client(); RespClient pub = server.client()) {
            sub.call("SUBSCRIBE", "a", "b");
            // the second channel confirmation arrives as a separate reply
            assertEquals("[\"subscribe\", \"b\", :2]", sub.read());

            assertTrue(sub.call("SET", "k", "v").startsWith("-ERR Can't execute 'set'"));
            assertEquals("[\"pong\", \"\"]", sub.call("PING"));

            assertEquals("[\"unsubscribe\", \"a\", :1]", sub.call("UNSUBSCRIBE", "a"));
            assertEquals(":0", pub.call("PUBLISH", "a", "x"));
            assertEquals(":1", pub.call("PUBLISH", "b", "x"));
            assertEquals("[\"message\", \"b\", \"x\"]", sub.read());

            assertEquals("[\"unsubscribe\", \"b\", :0]", sub.call("UNSUBSCRIBE"));
            assertEquals("+OK", sub.call("SET", "k", "v"), "back to normal mode after the last unsubscribe");
        }
    }

    @Test
    void disconnectedSubscribersAreCleanedUp() throws Exception {
        start();
        try (RespClient sub = server.client()) {
            sub.call("SUBSCRIBE", "temp");
        }
        assertTrue(TestSupport.await(3_000, () -> server.broker.subscriberCount("temp") == 0));
    }

    // ------------------------------------------------------- observability

    @Test
    void slowlogRecordsCommandsAboveTheThreshold() throws Exception {
        start();
        try (RespClient c = server.client()) {
            assertEquals(":0", c.call("SLOWLOG", "LEN"));
            assertEquals("+OK", c.call("CONFIG", "SET", "slowlog-log-slower-than", "0"));
            c.call("SET", "slow-key", "v");
            assertTrue(Long.parseLong(c.call("SLOWLOG", "LEN").substring(1)) >= 1);
            String entry = c.call("SLOWLOG", "GET", "5");
            assertTrue(entry.contains("\"slow-key\""), entry);
            assertEquals("+OK", c.call("SLOWLOG", "RESET"));
            // RESET clears the log, then the RESET command itself is recorded (it ran with threshold 0).
            assertEquals(":1", c.call("SLOWLOG", "LEN"));
        }
    }

    @Test
    void clientCommandsExposeConnectionInformation() throws Exception {
        start();
        try (RespClient a = server.client(); RespClient b = server.client()) {
            assertEquals("(nil)", a.call("CLIENT", "GETNAME"));
            assertEquals("+OK", a.call("CLIENT", "SETNAME", "worker-1"));
            assertEquals("\"worker-1\"", a.call("CLIENT", "GETNAME"));
            long idA = Long.parseLong(a.call("CLIENT", "ID").substring(1));
            long idB = Long.parseLong(b.call("CLIENT", "ID").substring(1));
            assertNotEquals(idA, idB);

            String list = a.call("CLIENT", "LIST");
            assertTrue(list.contains("name=worker-1"), list);
            assertTrue(list.contains("id=" + idB), list);
            assertTrue(a.call("CLIENT", "INFO").contains("id=" + idA));

            assertEquals(":1", a.call("CLIENT", "KILL", "ID", Long.toString(idB)));
            assertNull(b.read(), "the killed client must be disconnected");
        }
    }

    @Test
    void infoReportsCommandStatsMemoryAndKeyspace() throws Exception {
        start();
        try (RespClient c = server.client()) {
            c.call("SET", "a", "1");
            c.call("SET", "b", "2", "EX", "100");
            c.call("GET", "a");
            c.call("GET");
            String info = c.call("INFO");
            assertTrue(info.contains("cmdstat_set:calls=2"), info);
            assertTrue(info.contains("cmdstat_get:calls=2,"), info);
            assertTrue(info.contains("failed_calls=1"), "the arity error of GET must count as failed: " + info);
            assertTrue(info.contains("db0:keys=2,expires=1"), info);
            assertTrue(info.contains("connected_clients:1"), info);
            assertTrue(info.contains("maxmemory_policy:noeviction"), info);
            assertTrue(info.contains("role:primary"), info);
            assertTrue(info.contains("repl_backlog_active:1"), info);
            assertTrue(info.contains("repl_backlog_histlen:"), info);
            assertTrue(c.call("INFO", "commandstats").contains("# Commandstats"));
        }
    }

    @Test
    void monitorStreamsCommandsOfOtherClients() throws Exception {
        start();
        try (RespClient monitor = server.client(); RespClient worker = server.client()) {
            assertEquals("+OK", monitor.call("MONITOR"));
            worker.call("SET", "watched", "value");
            String line = monitor.read();
            assertTrue(line.startsWith("+") && line.contains("\"SET\" \"watched\" \"value\""), line);
        }
    }

    @Test
    void configGetAndSet() throws Exception {
        start();
        try (RespClient c = server.client()) {
            assertEquals("[\"maxmemory\", \"0\"]", c.call("CONFIG", "GET", "maxmemory"));
            assertEquals("+OK", c.call("CONFIG", "SET", "maxmemory", "10mb", "maxmemory-policy", "allkeys-random"));
            assertEquals("[\"maxmemory\", \"10485760\", \"maxmemory-policy\", \"allkeys-random\"]",
                    c.call("CONFIG", "GET", "maxmemory*"));
            assertTrue(c.call("CONFIG", "SET", "maxmemory", "lots").startsWith("-ERR Invalid size"));
            assertTrue(c.call("CONFIG", "SET", "maxmemory-policy", "bogus").startsWith("-ERR maxmemory-policy"));
            assertTrue(c.call("CONFIG", "SET", "port", "1234").contains("only be set at startup"));
            assertTrue(c.call("CONFIG", "SET", "nonsense", "1").contains("Unknown option"));
            assertEquals("+OK", c.call("CONFIG", "SET", "loglevel", "warn"));
            assertEquals("[\"loglevel\", \"warn\"]", c.call("CONFIG", "GET", "loglevel"));
            c.call("CONFIG", "SET", "loglevel", "info");
            assertTrue(c.call("CONFIG", "SET", "loglevel", "loud").startsWith("-ERR loglevel"));
            assertFalse(c.call("CONFIG", "GET", "*").contains("password"), "secrets must not be exposed");
        }
    }

    @Test
    void roleDescribesThePrimary() throws Exception {
        start();
        try (RespClient c = server.client()) {
            String role = c.call("ROLE");
            assertTrue(role.startsWith("[\"master\", :0, []"), role);
        }
    }

    // ------------------------------------------------------- binary safety

    /** Every byte value, in values and keys, must come back unchanged (UTF-8 decoding would corrupt this). */
    @Test
    void binaryValuesAndKeysRoundTripExactly() throws Exception {
        start();
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < 256; i++) {
            all.append((char) i);
        }
        String binary = all.toString();
        byte[] raw = binary.getBytes(StandardCharsets.ISO_8859_1);

        try (RespClient c = server.client()) {
            String request = "*3\r\n$3\r\nSET\r\n$256\r\n" + binary + "\r\n$256\r\n" + binary + "\r\n";
            c.sendRaw(request);
            assertEquals("+OK", c.read());

            c.sendRaw("*2\r\n$3\r\nGET\r\n$256\r\n" + binary + "\r\n");
            String reply = c.read();
            assertEquals("\"" + binary + "\"", reply);
            assertArrayEquals(raw,
                    reply.substring(1, reply.length() - 1).getBytes(StandardCharsets.ISO_8859_1));

            c.sendRaw("*2\r\n$6\r\nSTRLEN\r\n$256\r\n" + binary + "\r\n");
            assertEquals(":256", c.read(), "STRLEN counts bytes");
        }
    }

    @Test
    void binaryDataSurvivesTheAppendOnlyFile(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path file = dir.resolve("bin.aof");
        var persistence = new me.niteshh.redcake.config.PersistenceConfig(file,
                me.niteshh.redcake.config.PersistenceConfig.Fsync.ALWAYS);
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < 256; i++) {
            all.append((char) i);
        }
        server = new TestServer().withAof(persistence).start();
        try (RespClient c = server.client()) {
            c.sendRaw("*3\r\n$3\r\nSET\r\n$1\r\nb\r\n$256\r\n" + all + "\r\n");
            assertEquals("+OK", c.read());
        }
        server.close();

        server = new TestServer().withAof(persistence).start();
        assertEquals(all.toString(), server.stack.store().get("b"));
    }

    // ---------------------------------------------------- expiry propagation

    @Test
    void aPrimaryTurnsExpiryIntoAnExplicitDelInTheAofAndTheStream(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        Path file = dir.resolve("expiry.aof");
        server = new TestServer().withAof(new me.niteshh.redcake.config.PersistenceConfig(file,
                me.niteshh.redcake.config.PersistenceConfig.Fsync.ALWAYS)).start();

        try (RespClient replicaLink = server.client(); RespClient c = server.client()) {
            replicaLink.send("PSYNC", "?", "-1");
            assertTrue(replicaLink.read().contains("FULLRESYNC"));
            assertEquals("[\"REPLICAHELLO\", \"SYNCED\"]", replicaLink.read());

            assertEquals("+OK", c.call("SET", "short-lived", "v", "PX", "150"));
            assertTrue(replicaLink.read().contains("\"PXAT\""));      // the SET, with an absolute deadline
            replicaLink.setTimeout(5_000);
            assertEquals("[\"DEL\", \"short-lived\"]", replicaLink.read(),
                    "expiry must be propagated as DEL");
        }
        assertTrue(Files.readString(file, StandardCharsets.ISO_8859_1).contains("DEL"),
                "the AOF must record the expiry too");
    }

    @Test
    void aReplicaThatIsStillLoadingAnswersLoading() throws Exception {
        server = new TestServer().withRole(ReplicationRole.REPLICA).start();
        server.replicationStats.setLoading(true);
        try (RespClient c = server.client()) {
            assertTrue(c.call("GET", "k").startsWith("-LOADING"));
            assertEquals("+PONG", c.call("PING"), "PING stays available");
            server.replicationStats.setLoading(false);
            assertEquals("(nil)", c.call("GET", "k"));
        }
    }
}
