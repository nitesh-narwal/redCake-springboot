package me.niteshh.redcake.server;

import me.niteshh.redcake.config.AclConfig;
import me.niteshh.redcake.config.PersistenceConfig;
import me.niteshh.redcake.config.RedCakeAuthConfig;
import me.niteshh.redcake.config.RedCakeServerConfig;
import me.niteshh.redcake.replication.ReplicationConfig;
import me.niteshh.redcake.replication.ReplicationRole;
import me.niteshh.redcake.replication.replica.PrimaryConnection;
import me.niteshh.redcake.replication.replica.ReplicaSyncManager;
import me.niteshh.redcake.resp.BulkString;
import me.niteshh.redcake.security.AclRegistry;
import me.niteshh.redcake.stats.ReplicationStats;
import me.niteshh.redcake.support.RespClient;
import me.niteshh.redcake.support.TestServer;
import me.niteshh.redcake.support.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JSON documents through a real server: a record with an embedding over a socket, roles, maxmemory, the append-only
 * file (replay, and a rewrite of a document too large for one command), replication, transactions, and clients
 * working on the same document at the same time. Replies are shown the way {@link RespClient} renders them: a bulk
 * string is in quotes, an integer starts with a colon, an array is a bracketed list.
 */
class JsonServerTest {

    @TempDir
    Path dir;

    private final List<TestServer> servers = new ArrayList<>();
    private ReplicaSyncManager sync;
    private TestSupport.Stack replicaStack;

    @AfterEach
    void tearDown() {
        if (sync != null) {
            sync.stop();
        }
        if (replicaStack != null) {
            replicaStack.stop();
        }
        servers.forEach(TestServer::close);
    }

    private TestServer start(TestServer server) throws Exception {
        servers.add(server.start());
        return server;
    }

    /** A bulk string reply as the test client shows it. */
    private static String bulk(String text) {
        return "\"" + text + "\"";
    }

    private static String utf8(String unicode) {
        return new String(unicode.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    }

    private static String embedding(Random random, int dimensions) {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < dimensions; i++) {
            text.append(i > 0 ? "," : "").append((float) random.nextGaussian());
        }
        return text.append(']').toString();
    }

    /** What a machine-learning record looks like: text, an embedding, metadata and a status. */
    private static String record(Random random, int id) {
        return "{\"id\":\"doc-" + id + "\",\"text\":\"" + "lorem ipsum ".repeat(30) + "\",\"embedding\":" + embedding(random, 384)
                + ",\"meta\":{\"source\":\"web\",\"lang\":\"en\",\"tags\":[\"a\",\"b\"]},\"status\":\"new\",\"attempts\":0}";
    }

    // ------------------------------------------------------------------------------------------ over the wire

    @Test
    void aRecordWithAnEmbeddingIsStoredUpdatedFieldByFieldAndReadBack() throws Exception {
        TestServer server = start(new TestServer());
        Random random = new Random(1);
        String vector = embedding(random, 384);
        String record = "{\"id\":\"doc-1\",\"text\":\"" + "lorem ipsum ".repeat(30) + "\",\"embedding\":" + vector
                + ",\"meta\":{\"source\":\"web\",\"lang\":\"en\",\"tags\":[\"a\",\"b\"]},\"status\":\"new\",\"attempts\":0}";
        try (RespClient c = server.client()) {
            assertEquals("+OK", c.call("JSON.SET", "doc:1", "$", record));
            assertEquals("+ReJSON-RL", c.call("TYPE", "doc:1"));
            assertEquals(bulk("[\"new\"]"), c.call("JSON.GET", "doc:1", "$.status"));
            assertEquals(bulk("\"new\""), c.call("JSON.GET", "doc:1", ".status"));
            assertEquals(":384", c.call("JSON.ARRLEN", "doc:1", ".embedding"));
            assertEquals("[:384]", c.call("JSON.ARRLEN", "doc:1", "$.embedding"));
            assertEquals(bulk(vector), c.call("JSON.GET", "doc:1", ".embedding"), "the embedding comes back digit for digit");

            // what an indexing worker does: claim the record, count the attempt, add tags, mark it done
            assertEquals("+OK", c.call("JSON.SET", "doc:1", "$.status", "\"processing\""));
            assertEquals(bulk("[1]"), c.call("JSON.NUMINCRBY", "doc:1", "$.attempts", "1"));
            assertEquals("[:4]", c.call("JSON.ARRAPPEND", "doc:1", "$.meta.tags", "\"c\"", "\"d\""));
            assertEquals("+OK", c.call("JSON.MERGE", "doc:1", "$", "{\"status\":\"done\",\"meta\":{\"lang\":null,\"indexed\":true}}"));
        }
        try (RespClient c = server.client()) {
            assertEquals(bulk("{\"source\":\"web\",\"tags\":[\"a\",\"b\",\"c\",\"d\"],\"indexed\":true}"), c.call("JSON.GET", "doc:1", ".meta"));
            assertEquals(bulk("\"done\""), c.call("JSON.GET", "doc:1", ".status"));
            assertEquals(bulk("1"), c.call("JSON.GET", "doc:1", ".attempts"));
            assertEquals(bulk(vector), c.call("JSON.GET", "doc:1", ".embedding"), "untouched by the updates");
            assertEquals(bulk("[\"doc-1\"]"), c.call("JSON.GET", "doc:1", "$.id"));
        }
    }

    @Test
    void whatIsSentIsReadBackByteForByte() throws Exception {
        TestServer server = start(new TestServer());
        String document = "{\"" + utf8("é") + "\":\"" + utf8("日本語 €😀") + "\",\"n\":[1,2.5,-300.0],\"e\":{}}";
        try (RespClient c = server.client()) {
            assertEquals("+OK", c.call("JSON.SET", "u", "$", document));
            assertEquals(bulk(document), c.call("JSON.GET", "u"));
            assertEquals("[:" + utf8("日本語 €😀").length() + "]", c.call("JSON.STRLEN", "u", "$[\"" + utf8("é") + "\"]"), "lengths are in bytes");
            assertTrue(c.call("JSON.SET", "u", "$.bad", "\"" + "Ã" + "\"").startsWith("-ERR invalid JSON"), "invalid UTF-8 is refused");
            assertEquals(bulk(document), c.call("JSON.GET", "u"), "and changes nothing");
        }
    }

    @Test
    void aRefusedCommandLeavesNothingBehindForTheNextOne() throws Exception {
        TestServer server = start(new TestServer());
        try (RespClient c = server.client()) {
            assertTrue(c.call("JSON.SET", "k", "$", "{bad json").startsWith("-ERR invalid JSON"));
            assertEquals("(nil)", c.call("JSON.GET", "k"));
            assertTrue(c.call("JSON.SET", "k", "$.a", "1").startsWith("-ERR new objects must be created at the root"));
            assertEquals("+OK", c.call("JSON.SET", "k", "$", "{\"a\":[1],\"b\":[1]}"));
            // two equal arguments in one command are two values, not one tree shared by two places
            assertEquals("[:3, :3]", c.call("JSON.ARRAPPEND", "k", "$.*", "{\"x\":1}", "{\"x\":1}"));
            assertEquals("+OK", c.call("JSON.SET", "k", "$.a[1].x", "99"));
            assertEquals(bulk("[[1,{\"x\":99},{\"x\":1}]]"), c.call("JSON.GET", "k", "$.a"));
            assertEquals(bulk("[[1,{\"x\":1},{\"x\":1}]]"), c.call("JSON.GET", "k", "$.b"));
            assertTrue(c.call("JSON.ARRAPPEND", "k", "$.a", "{oops").startsWith("-ERR invalid JSON"));
            assertEquals("+OK", c.call("JSON.SET", "k", "$.c", "{\"fresh\":true}"), "the failed command's leftovers do not leak into this one");
            assertEquals(bulk("{\"fresh\":true}"), c.call("JSON.GET", "k", ".c"));
        }
    }

    // ------------------------------------------------------------------------------------------------ roles

    @Test
    void rolesDecideWhoMayChangeADocument() throws Exception {
        Path file = dir.resolve("users.acl");
        Files.writeString(file, "user bob readwrite %s\nuser carol readonly %s\n".formatted(
                AclRegistry.hash("b".getBytes(StandardCharsets.UTF_8)), AclRegistry.hash("c".getBytes(StandardCharsets.UTF_8))));
        TestServer server = start(new TestServer().withAcl(new AclRegistry(new AclConfig(file))));
        try (RespClient bob = server.client()) {
            assertEquals("+OK", bob.call("AUTH", "bob", "b"));
            assertEquals("+OK", bob.call("JSON.SET", "d", "$", "{\"a\":[1],\"n\":1,\"t\":true,\"s\":\"x\"}"));
            assertEquals("[:2]", bob.call("JSON.ARRAPPEND", "d", "$.a", "2"));
            assertEquals("+OK", bob.call("JSON.MERGE", "d", "$", "{\"m\":1}"));
        }
        try (RespClient carol = server.client()) {
            assertEquals("+OK", carol.call("AUTH", "carol", "c"));
            assertEquals(bulk("[1]"), carol.call("JSON.GET", "d", "$.n"));
            assertEquals("[\"integer\"]", carol.call("JSON.TYPE", "d", "$.n"));
            assertEquals("[:2]", carol.call("JSON.ARRLEN", "d", "$.a"));
            assertEquals("[\"[1]\", (nil)]", carol.call("JSON.MGET", "d", "missing", "$.n"));
            for (String[] write : new String[][]{
                    {"JSON.SET", "d", "$.n", "2"}, {"JSON.DEL", "d", "$.n"}, {"JSON.ARRAPPEND", "d", "$.a", "3"},
                    {"JSON.NUMINCRBY", "d", "$.n", "1"}, {"JSON.TOGGLE", "d", "$.t"}, {"JSON.CLEAR", "d", "$.a"},
                    {"JSON.MERGE", "d", "$", "{}"}, {"JSON.STRAPPEND", "d", "$.s", "\"y\""}, {"JSON.ARRPOP", "d", "$.a"},
                    {"JSON.ARRTRIM", "d", "$.a", "0", "0"}, {"JSON.ARRINSERT", "d", "$.a", "0", "0"}, {"JSON.FORGET", "d", "$.n"}}) {
                assertTrue(carol.call(write).startsWith("-NOPERM"), String.join(" ", write));
            }
            assertEquals(bulk("[1]"), carol.call("JSON.GET", "d", "$.n"), "nothing was changed");
            assertEquals(bulk("[[1,2]]"), carol.call("JSON.GET", "d", "$.a"));
        }
    }

    // ------------------------------------------------------------------------------------------ maxmemory

    @Test
    void maxmemoryStopsDocumentsFromGrowingButNotFromShrinkingOrBeingRead() throws Exception {
        TestServer server = start(new TestServer());
        try (RespClient c = server.client()) {
            assertEquals("+OK", c.call("JSON.SET", "d", "$", "{\"a\":[1,2,3],\"s\":\"x\"}"));
            server.memoryConfig.setMaxBytes(server.stack.store().usedMemory() - 1);
            assertTrue(c.call("JSON.SET", "d", "$.more", "1").startsWith("-OOM"));
            assertTrue(c.call("JSON.ARRAPPEND", "d", "$.a", "4").startsWith("-OOM"));
            assertTrue(c.call("JSON.SET", "other", "$", "{}").startsWith("-OOM"));
            assertEquals(bulk("[[1,2,3]]"), c.call("JSON.GET", "d", "$.a"), "reads keep working");
            assertEquals(":1", c.call("JSON.DEL", "d", "$.s"), "deleting is always allowed");
            assertEquals(bulk("3"), c.call("JSON.ARRPOP", "d", ".a"), "so is popping");
            server.memoryConfig.setMaxBytes(0);
            assertEquals("+OK", c.call("JSON.SET", "d", "$.more", "1"));
        }
    }

    // ------------------------------------------------------------------------------ append-only file

    @Test
    void theAppendOnlyFileReplaysAndRewritesDocumentsEvenWhenTheyAreTooBigForOneCommand() throws Exception {
        Path file = dir.resolve("documents.aof");
        PersistenceConfig persistence = new PersistenceConfig(file, PersistenceConfig.Fsync.ALWAYS);
        Random random = new Random(7);
        List<String> expected = new ArrayList<>();

        try (TestServer first = new TestServer().withAof(persistence).start(); RespClient c = first.client()) {
            assertEquals("+OK", c.call("JSON.SET", "rec", "$", record(random, 1)));
            assertEquals("+OK", c.call("JSON.SET", "rec", "$.status", "\"done\""));
            c.call("JSON.NUMINCRBY", "rec", "$.attempts", "2");
            c.call("JSON.MERGE", "rec", "$", "{\"meta\":{\"lang\":null,\"extra\":[1,2]}}");
            c.call("JSON.ARRAPPEND", "rec", "$.meta.tags", "\"z\"");
            // a document that grows far beyond what one command can carry, one append at a time
            assertEquals("+OK", c.call("JSON.SET", "big", "$", "{\"series\":[],\"log\":\"\",\"nested\":{\"k\":[]}}"));
            for (int part = 0; part < 90; part++) {
                List<String> numbers = new ArrayList<>(List.of("JSON.ARRAPPEND", "big", "$.series"));
                for (int i = 0; i < 800; i++) {
                    numbers.add(Integer.toString(random.nextInt(1_000_000)));
                }
                assertEquals("[:" + (part + 1) * 800 + "]", c.call(numbers.toArray(String[]::new)));
                c.call("JSON.STRAPPEND", "big", "$.log", "\"" + "0123456789abcdef".repeat(2500) + "\"");
                c.call("JSON.ARRAPPEND", "big", "$.nested.k", "{\"part\":" + part + ",\"values\":[0.5,1.5,2.5]}");
            }
            c.call("JSON.DEL", "rec", "$.text");
            assertEquals(":72000", c.call("JSON.ARRLEN", "big", ".series"));
            expected.add(c.call("JSON.GET", "rec"));
            expected.add(c.call("JSON.GET", "big"));
            assertTrue(expected.get(1).length() > 3_000_000, "the document is " + expected.get(1).length() + " bytes");
        }
        String logged = Files.readString(file, StandardCharsets.ISO_8859_1);
        assertTrue(logged.contains("JSON.SET") && logged.contains("JSON.ARRAPPEND") && logged.contains("JSON.MERGE"));

        // 1. replay of the plain log
        try (TestServer second = new TestServer().withAof(persistence).start(); RespClient c = second.client()) {
            assertEquals(expected.get(0), c.call("JSON.GET", "rec"));
            assertEquals(expected.get(1), c.call("JSON.GET", "big"));
            assertEquals("+ReJSON-RL", c.call("TYPE", "big"));

            // 2. the rewrite describes the 3 MB document with commands the parser accepts
            assertTrue(c.call("BGREWRITEAOF").startsWith("+"));
            String rewritten = Files.readString(file, StandardCharsets.ISO_8859_1);
            assertFalse(rewritten.contains("JSON.NUMINCRBY") || rewritten.contains("JSON.MERGE") || rewritten.contains("JSON.DEL"),
                    "the history became the documents themselves");
            assertTrue(rewritten.contains("JSON.ARRAPPEND") && rewritten.contains("JSON.STRAPPEND"),
                    "the long array and the long string are rebuilt in pieces");
            assertEquals("+OK", c.call("JSON.SET", "rec", "$.after", "\"rewrite\""));
        }

        // 3. replay of the rewritten file plus the command appended after it
        try (TestServer third = new TestServer().withAof(persistence).start(); RespClient c = third.client()) {
            assertEquals(expected.get(1), c.call("JSON.GET", "big"));
            assertEquals(bulk("[\"rewrite\"]"), c.call("JSON.GET", "rec", "$.after"));
            assertEquals(":72000", c.call("JSON.ARRLEN", "big", ".series"));
        }
    }

    // ----------------------------------------------------------------------------------------- replication

    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void startReplicaOf(TestServer primary) throws Exception {
        replicaStack = TestSupport.newStack();
        sync = new ReplicaSyncManager(
                new PrimaryConnection(),
                new ReplicationConfig(ReplicationRole.REPLICA, "127.0.0.1", primary.port),
                TestSupport.allCommands(replicaStack.store(), ReplicationRole.REPLICA),
                new RedCakeAuthConfig(null),
                replicaStack.store(),
                new RedCakeServerConfig(freePort(), "127.0.0.1"),
                new ReplicationStats());
        sync.start();
    }

    /** Runs a read command on the replica and shows the reply like the client would. */
    private String onReplica(String... command) {
        Object reply = TestSupport.allCommands(replicaStack.store(), ReplicationRole.REPLICA).handle(List.of(command));
        return reply instanceof BulkString b ? bulk(b.value()) : String.valueOf(reply);
    }

    @Test
    void aReplicaReceivesDocumentsInTheFullSyncAndThenFollowsTheStream() throws Exception {
        TestServer primary = start(new TestServer());
        Random random = new Random(5);
        try (RespClient c = primary.client()) {
            for (int i = 0; i < 40; i++) {
                assertEquals("+OK", c.call("JSON.SET", "doc:" + i, "$", record(random, i)));
            }
            assertEquals("+OK", c.call("JSON.SET", "big", "$", "{\"a\":[" + "1,2,3,4,5,6,7,8,9,".repeat(25_000) + "0],\"t\":\"" + "word ".repeat(50_000) + "\"}"));
            c.call("PEXPIREAT", "doc:3", Long.toString(System.currentTimeMillis() + 3_600_000));
        }
        startReplicaOf(primary);
        assertTrue(TestSupport.await(15_000, () -> replicaStack.store().size() == 41), "the full sync did not arrive");
        assertTrue(replicaStack.store().pttl("doc:3") > 3_000_000, "the TTL travels with the key");

        try (RespClient c = primary.client()) {
            for (int i = 0; i < 40; i++) {
                assertEquals(bulk("[1]"), c.call("JSON.NUMINCRBY", "doc:" + i, "$.attempts", "1"));
                c.call("JSON.SET", "doc:" + i, "$.status", "\"indexed\"");
                c.call("JSON.ARRAPPEND", "doc:" + i, "$.meta.tags", "\"live\"");
            }
            c.call("JSON.DEL", "doc:7", "$");
            c.call("JSON.MERGE", "doc:8", "$", "{\"meta\":{\"source\":null}}");
        }
        assertTrue(TestSupport.await(15_000, () -> replicaStack.store().size() == 40 && onReplica("JSON.GET", "doc:39", ".status").equals(bulk("\"indexed\""))),
                "the stream did not catch up");
        try (RespClient c = primary.client()) {
            for (int i = 0; i < 40; i++) {
                if (i == 7) {
                    assertFalse(replicaStack.store().exists("doc:7"), "a deleted document is deleted on the replica too");
                    continue;
                }
                assertEquals(c.call("JSON.GET", "doc:" + i), onReplica("JSON.GET", "doc:" + i), "doc:" + i);
            }
            assertEquals(c.call("JSON.GET", "big"), onReplica("JSON.GET", "big"));
        }
        assertEquals(primary.stack.store().usedMemory(), replicaStack.store().usedMemory(), "the replica counts the same bytes");
    }

    // ------------------------------------------------------------------ transactions and many clients

    @Test
    void transactionsCanContainJsonCommandsAndWatchSeesThem() throws Exception {
        TestServer server = start(new TestServer());
        try (RespClient a = server.client(); RespClient b = server.client()) {
            assertEquals("+OK", a.call("JSON.SET", "d", "$", "{\"n\":0,\"l\":[]}"));
            assertEquals("+OK", a.call("MULTI"));
            assertEquals("+QUEUED", a.call("JSON.NUMINCRBY", "d", "$.n", "5"));
            assertEquals("+QUEUED", a.call("JSON.ARRAPPEND", "d", "$.l", "1", "2"));
            assertEquals("+QUEUED", a.call("JSON.SET", "d", "$.nope.deeper", "1"));
            assertEquals("+QUEUED", a.call("JSON.GET", "d", "$"));
            assertEquals("[\"[5]\", [:2], -ERR Path '$.nope.deeper' does not exist, \"[{\"n\":5,\"l\":[1,2]}]\"]", a.call("EXEC"),
                    "a command that fails does not undo the ones before it, and the ones after it still run");

            assertEquals("+OK", a.call("WATCH", "d"));
            assertEquals("+OK", b.call("JSON.SET", "d", "$.n", "100")); // somebody else changes it
            assertEquals("+OK", a.call("MULTI"));
            assertEquals("+QUEUED", a.call("JSON.SET", "d", "$.n", "1"));
            assertEquals("(nil)", a.call("EXEC"), "the transaction is aborted because the document changed");
            assertEquals(bulk("[100]"), a.call("JSON.GET", "d", "$.n"));
        }
    }

    @Test
    void clientsWorkingOnOneDocumentLoseNothing() throws Exception {
        TestServer server = start(new TestServer());
        try (RespClient c = server.client()) {
            assertEquals("+OK", c.call("JSON.SET", "shared", "$", "{\"counter\":0,\"items\":[],\"by\":{}}"));
        }
        int writers = 6;
        int each = 300;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            int writer = w;
            Thread thread = new Thread(() -> {
                try (RespClient c = server.client()) {
                    for (int i = 0; i < each; i++) {
                        assertTrue(c.call("JSON.NUMINCRBY", "shared", "$.counter", "1").startsWith("\"["));
                        assertTrue(c.call("JSON.ARRAPPEND", "shared", "$.items",
                                "{\"w\":" + writer + ",\"i\":" + i + ",\"v\":[0.5,1.5]}").startsWith("[:"));
                        assertEquals("+OK", c.call("JSON.SET", "shared", "$.by.w" + writer, Integer.toString(i)));
                        if (i % 50 == 0) {
                            assertTrue(c.call("JSON.GET", "shared", "$.items[0]").startsWith("\"["));
                        }
                    }
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
        String text;
        try (RespClient c = server.client()) {
            assertEquals(bulk("[" + writers * each + "]"), c.call("JSON.GET", "shared", "$.counter"), "every increment counted");
            assertEquals("[:" + writers * each + "]", c.call("JSON.ARRLEN", "shared", "$.items"), "every append kept");
            for (int w = 0; w < writers; w++) {
                assertEquals(bulk("[" + (each - 1) + "]"), c.call("JSON.GET", "shared", "$.by.w" + w));
            }
            String reply = c.call("JSON.GET", "shared");
            text = reply.substring(1, reply.length() - 1);
        }
        // the byte count kept through thousands of concurrent changes equals the one of the same document written afresh
        TestServer other = start(new TestServer());
        try (RespClient o = other.client()) {
            assertEquals("+OK", o.call("JSON.SET", "shared", "$", text));
        }
        assertEquals(other.stack.store().usedMemory(), server.stack.store().usedMemory());
    }

    @Test
    void manyClientsStoreBigRecordsAtTheSameTime() throws Exception {
        TestServer server = start(new TestServer());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            int thread = t;
            Thread worker = new Thread(() -> {
                try (RespClient c = server.client()) {
                    Random random = new Random(thread);
                    for (int i = 0; i < 40; i++) {
                        assertEquals("+OK", c.call("JSON.SET", "r:" + thread + ":" + i, "$", record(random, i)));
                    }
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            });
            threads.add(worker);
            worker.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
        try (RespClient c = server.client()) {
            assertEquals(":160", c.call("DBSIZE"));
            assertEquals("[:384]", c.call("JSON.ARRLEN", "r:3:39", "$.embedding"));
            assertEquals(bulk("[\"doc-39\"]"), c.call("JSON.GET", "r:3:39", "$.id"));
        }
    }
}
