package me.niteshh.redcake.server;

import me.niteshh.redcake.config.AclConfig;
import me.niteshh.redcake.config.PersistenceConfig;
import me.niteshh.redcake.config.RedCakeAuthConfig;
import me.niteshh.redcake.config.RedCakeServerConfig;
import me.niteshh.redcake.replication.ReplicationConfig;
import me.niteshh.redcake.replication.ReplicationRole;
import me.niteshh.redcake.replication.replica.PrimaryConnection;
import me.niteshh.redcake.replication.replica.ReplicaSyncManager;
import me.niteshh.redcake.security.AclRegistry;
import me.niteshh.redcake.stats.ReplicationStats;
import me.niteshh.redcake.support.RespClient;
import me.niteshh.redcake.support.TestServer;
import me.niteshh.redcake.support.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Vector sets through a real server: binary blobs over a socket, roles, maxmemory, the append-only file (replay and
 * rewrite), replication (full sync and live stream), transactions and parallel clients.
 */
class VectorServerTest {

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

    private static String blob(float... values) {
        ByteBuffer buffer = ByteBuffer.allocate(4 * values.length).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : values) {
            buffer.putFloat(v);
        }
        return new String(buffer.array(), StandardCharsets.ISO_8859_1);
    }

    private static float[] randomVector(Random random, int dim) {
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) {
            v[i] = (float) random.nextGaussian();
        }
        return v;
    }

    /** Fills a set with clustered vectors (the shape of real embeddings) and returns them. */
    private static float[][] fill(RespClient client, String key, int count, int dim, long seed, String... options)
            throws Exception {
        Random random = new Random(seed);
        float[][] centres = new float[8][];
        for (int c = 0; c < centres.length; c++) {
            centres[c] = randomVector(random, dim);
        }
        float[][] out = new float[count][dim];
        for (int n = 0; n < count; n++) {
            float[] centre = centres[random.nextInt(centres.length)];
            for (int i = 0; i < dim; i++) {
                out[n][i] = centre[i] + 0.4f * (float) random.nextGaussian();
            }
            List<String> command = new ArrayList<>(List.of("VADD", key, "FP32", blob(out[n]), "item:" + n));
            command.addAll(List.of(options));
            assertEquals(":1", client.call(command.toArray(String[]::new)));
        }
        return out;
    }

    private static List<String> names(String reply) {
        String inner = reply.substring(1, reply.length() - 1);
        List<String> out = new ArrayList<>();
        for (String part : inner.split(", ")) {
            if (!part.isEmpty()) {
                out.add(part.replace("\"", ""));
            }
        }
        return out;
    }

    private static double overlap(List<String> a, List<String> b) {
        Set<String> common = new HashSet<>(a);
        common.retainAll(b);
        return a.isEmpty() ? 1 : (double) common.size() / a.size();
    }

    // ------------------------------------------------------------------ over the wire

    @Test
    void embeddingsTravelAsBinaryBlobsAndComeBackWithTheirAttributes() throws Exception {
        TestServer server = start(new TestServer());
        Random random = new Random(1);
        float[] first = randomVector(random, 1536);
        float[] second = randomVector(random, 1536);
        try (RespClient c = server.client()) {
            assertEquals(":1", c.call("VADD", "docs", "FP32", blob(first), "doc:1", "SETATTR", "{\"title\":\"one\"}"));
            assertEquals(":1", c.call("VADD", "docs", "FP32", blob(second), "doc:2"));
            assertEquals(":2", c.call("VCARD", "docs"));
            assertEquals(":1536", c.call("VDIM", "docs"));
            assertEquals("+vectorset", c.call("TYPE", "docs"));
            assertEquals("[\"doc:1\", \"doc:2\"]", c.call("VSIM", "docs", "FP32", blob(first), "COUNT", "2"));
            assertEquals("[\"doc:2\", \"doc:1\"]", c.call("VSIM", "docs", "FP32", blob(second), "COUNT", "2"));
            assertEquals("[\"doc:1\", \"{\"title\":\"one\"}\"]",
                    c.call("VSIM", "docs", "ELE", "doc:1", "COUNT", "1", "WITHATTRIBS"));
            String raw = c.call("VEMB", "docs", "doc:1", "RAW");
            assertTrue(raw.startsWith("[\"int8\", \""), raw);
            assertEquals("(nil)", c.call("VEMB", "docs", "nope"));
            assertTrue(c.call("VADD", "docs", "FP32", blob(1, 2, 3), "wrong").contains("dimension mismatch"));
        }
    }

    // ------------------------------------------------------------------ roles

    @Test
    void rolesDecideWhoMayWriteVectorsAndOnlyAdminsMayRestore() throws Exception {
        Path file = dir.resolve("users.acl");
        Files.writeString(file, "user alice admin %s\nuser bob readwrite %s\nuser carol readonly %s\n".formatted(
                AclRegistry.hash("a".getBytes(StandardCharsets.UTF_8)),
                AclRegistry.hash("b".getBytes(StandardCharsets.UTF_8)),
                AclRegistry.hash("c".getBytes(StandardCharsets.UTF_8))));
        TestServer server = start(new TestServer().withAcl(new AclRegistry(new AclConfig(file))));

        try (RespClient bob = server.client()) {
            assertEquals("+OK", bob.call("AUTH", "bob", "b"));
            assertEquals(":1", bob.call("VADD", "v", "VALUES", "2", "1", "0", "a"));
            assertEquals(":1", bob.call("VADD", "v", "VALUES", "2", "0", "1", "b"));
            assertEquals(":1", bob.call("VSETATTR", "v", "a", "{}"));
            assertEquals(":1", bob.call("VREM", "v", "b"));
            assertTrue(bob.call("VRESTORE", "v", "END").startsWith("-NOPERM"), "restoring is administrative");
        }
        try (RespClient carol = server.client()) {
            assertEquals("+OK", carol.call("AUTH", "carol", "c"));
            assertEquals("[\"a\"]", carol.call("VSIM", "v", "ELE", "a"));
            assertEquals(":1", carol.call("VCARD", "v"));
            assertEquals("\"{}\"", carol.call("VGETATTR", "v", "a"));
            assertTrue(carol.call("VADD", "v", "VALUES", "2", "1", "1", "c").startsWith("-NOPERM"));
            assertTrue(carol.call("VREM", "v", "a").startsWith("-NOPERM"));
            assertTrue(carol.call("VSETATTR", "v", "a", "x").startsWith("-NOPERM"));
            assertEquals(":1", carol.call("VCARD", "v"), "nothing was modified");
        }
        try (RespClient alice = server.client()) {
            assertEquals("+OK", alice.call("AUTH", "alice", "a"));
            assertFalse(alice.call("VRESTORE", "v", "END").startsWith("-NOPERM"));
        }
    }

    // ------------------------------------------------------------------ maxmemory

    @Test
    void maxmemoryRefusesToGrowAVectorSetButStillAllowsSearchesAndRemovals() throws Exception {
        TestServer server = start(new TestServer());
        server.memoryConfig.setMaxBytes(60_000);
        try (RespClient c = server.client()) {
            // the first vector allocates a whole page, which is more than the whole budget
            assertEquals(":1", c.call("VADD", "v", "FP32", blob(1, 0, 0, 0), "a"));
            assertTrue(server.stack.store().usedMemory() > 60_000);
            assertTrue(c.call("VADD", "v", "FP32", blob(0, 1, 0, 0), "b").startsWith("-OOM"), "growing is refused");
            assertTrue(c.call("VSETATTR", "v", "a", "x").startsWith("-OOM"));
            assertTrue(c.call("SET", "other", "x").startsWith("-OOM"));
            assertEquals("[\"a\"]", c.call("VSIM", "v", "ELE", "a"), "reads keep working");
            assertEquals(":1", c.call("VREM", "v", "a"), "removals are always allowed");
            assertEquals("+OK", c.call("SET", "other", "x"), "the empty set released its memory");
        }
    }

    // ------------------------------------------------------------------ append-only file

    @Test
    void theAppendOnlyFileReplaysAndRewritesVectorSets() throws Exception {
        Path file = dir.resolve("vectors.aof");
        PersistenceConfig persistence = new PersistenceConfig(file, PersistenceConfig.Fsync.ALWAYS);
        float[][] vectors;
        List<String> queryExpected = new ArrayList<>();
        float[] query = randomVector(new Random(99), 64);

        try (TestServer first = new TestServer().withAof(persistence).start(); RespClient c = first.client()) {
            vectors = fill(c, "v", 700, 64, 3, "NOQUANT"); // floats: 64 * 4 = 256 B per vector, several dump chunks
            assertEquals(":1", c.call("VSETATTR", "v", "item:5", "{\"a\":1}"));
            for (int i = 0; i < 700; i += 7) {
                assertEquals(":1", c.call("VREM", "v", "item:" + i));
            }
            assertEquals(":0", c.call("VADD", "v", "FP32", blob(vectors[1]), "item:1"), "an update, logged too");
            queryExpected.add(c.call("VSIM", "v", "FP32", blob(query), "COUNT", "10"));
            queryExpected.add(c.call("VSIM", "v", "ELE", "item:3", "COUNT", "10", "WITHSCORES"));
            assertEquals(":600", c.call("VCARD", "v"));
        }
        String logged = Files.readString(file, StandardCharsets.ISO_8859_1);
        assertTrue(logged.contains("VADD") && !logged.contains("VRESTORE"));
        assertTrue(logged.contains("FP32"), "VALUES are logged in their binary form");

        // 1. replay of the plain log
        try (TestServer second = new TestServer().withAof(persistence).start(); RespClient c = second.client()) {
            assertEquals(":600", c.call("VCARD", "v"));
            assertEquals("\"{\"a\":1}\"", c.call("VGETATTR", "v", "item:5"));
            assertEquals(queryExpected.get(0), c.call("VSIM", "v", "FP32", blob(query), "COUNT", "10"));
            assertEquals(queryExpected.get(1), c.call("VSIM", "v", "ELE", "item:3", "COUNT", "10", "WITHSCORES"));

            // 2. compaction: the whole set becomes a handful of VRESTORE commands
            assertTrue(c.call("BGREWRITEAOF").startsWith("+"));
            // The dump carries the graph links as well as the vectors, so it is not smaller than a log of plain
            // inserts - in exchange, loading it needs no searching.
            assertFalse(Files.readString(file, StandardCharsets.ISO_8859_1).contains("VADD"),
                    "the whole history became restore commands");
            assertEquals(":1", c.call("VADD", "v", "FP32", blob(randomVector(new Random(7), 64)), "after-rewrite"));
            queryExpected.set(0, c.call("VSIM", "v", "FP32", blob(query), "COUNT", "10"));
        }
        String rewritten = Files.readString(file, StandardCharsets.ISO_8859_1);
        assertTrue(rewritten.contains("VRESTORE") && rewritten.contains("HEADER") && rewritten.contains("END"));

        // 3. replay of rewritten file plus the command appended after it
        try (TestServer third = new TestServer().withAof(persistence).start(); RespClient c = third.client()) {
            assertEquals(":601", c.call("VCARD", "v"));
            assertEquals(":1", c.call("VISMEMBER", "v", "after-rewrite"));
            assertEquals(queryExpected.get(0), c.call("VSIM", "v", "FP32", blob(query), "COUNT", "10"));
            assertEquals("\"{\"a\":1}\"", c.call("VGETATTR", "v", "item:5"));
        }
    }

    // ------------------------------------------------------------------ replication

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

    @Test
    void aReplicaReceivesTheWholeIndexInTheFullSyncAndThenFollowsTheStream() throws Exception {
        TestServer primary = start(new TestServer());
        float[][] vectors;
        try (RespClient c = primary.client()) {
            vectors = fill(c, "v", 1200, 48, 5);
            c.call("VSETATTR", "v", "item:9", "{\"x\":9}");
            c.call("PEXPIREAT", "v", Long.toString(System.currentTimeMillis() + 3_600_000));
        }

        startReplicaOf(primary);
        assertTrue(TestSupport.await(10_000, () -> replicaSet() != null && !replicaSet().isRestoring()
                && replicaSet().size() == 1200), "the dump did not arrive");
        assertTrue(replicaStack.store().pttl("v") > 3_000_000, "the TTL travels with the key");

        // live writes after the sync
        try (RespClient c = primary.client()) {
            Random random = new Random(11);
            for (int i = 0; i < 100; i++) {
                assertEquals(":1", c.call("VADD", "v", "FP32", blob(randomVector(random, 48)), "live:" + i));
            }
            for (int i = 0; i < 1200; i += 20) {
                assertEquals(":1", c.call("VREM", "v", "item:" + i));
            }
            assertEquals(":" + (1200 + 100 - 60), c.call("VCARD", "v"));
        }
        assertTrue(TestSupport.await(10_000, () -> replicaSet().size() == 1240), "the stream did not catch up");

        me.niteshh.redcake.command.CommandHandler onReplica =
                TestSupport.allCommands(replicaStack.store(), ReplicationRole.REPLICA);
        try (RespClient onPrimary = primary.client()) {
            double total = 0;
            int asked = 0;
            Random random = new Random(21);
            for (int q = 0; q < 40; q++) {
                int index = 1 + random.nextInt(1199);
                if (index % 20 == 0) {
                    continue; // removed above
                }
                List<String> fromPrimary = names(onPrimary.call("VSIM", "v", "ELE", "item:" + index, "COUNT", "10"));
                List<String> fromReplica = new ArrayList<>();
                for (me.niteshh.redcake.resp.RespValue value : ((me.niteshh.redcake.resp.ArrayValue) onReplica.handle(
                        List.of("VSIM", "v", "ELE", "item:" + index, "COUNT", "10"))).elements()) {
                    fromReplica.add(((me.niteshh.redcake.resp.BulkString) value).value());
                }
                total += overlap(fromPrimary, fromReplica);
                asked++;
            }
            assertTrue(total / asked > 0.9, "primary and replica answer alike: " + total / asked);
            assertEquals(onPrimary.call("VGETATTR", "v", "item:9"), "\"{\"x\":9}\"");
            assertEquals(new me.niteshh.redcake.resp.BulkString("{\"x\":9}"),
                    onReplica.handle(List.of("VGETATTR", "v", "item:9")));
        }
    }

    private me.niteshh.redcake.vector.VectorSet replicaSet() {
        return (me.niteshh.redcake.vector.VectorSet) replicaStack.store()
                .peek("v", me.niteshh.redcake.store.ValueType.VECTORSET);
    }

    // ------------------------------------------------------------------ transactions and parallel clients

    @Test
    void transactionsCanContainVectorWritesAndWatchSeesThem() throws Exception {
        TestServer server = start(new TestServer());
        try (RespClient a = server.client(); RespClient b = server.client()) {
            assertEquals("+OK", a.call("MULTI"));
            assertEquals("+QUEUED", a.call("VADD", "v", "VALUES", "2", "1", "0", "x"));
            assertEquals("+QUEUED", a.call("VADD", "v", "VALUES", "2", "0", "1", "y"));
            assertEquals("+QUEUED", a.call("VCARD", "v"));
            assertEquals("[:1, :1, :2]", a.call("EXEC"));

            assertEquals("+OK", a.call("WATCH", "v"));
            assertEquals(":1", b.call("VADD", "v", "VALUES", "2", "1", "1", "z")); // somebody else changes it
            assertEquals("+OK", a.call("MULTI"));
            assertEquals("+QUEUED", a.call("VREM", "v", "x"));
            assertEquals("(nil)", a.call("EXEC"), "the transaction is aborted because the key changed");
            assertEquals(":3", a.call("VCARD", "v"));
        }
    }

    @Test
    void severalClientsInsertAtTheSameTimeAndNothingIsLost() throws Exception {
        TestServer server = start(new TestServer());
        try (RespClient c = server.client()) {
            assertEquals(":1", c.call("VADD", "v", "FP32", blob(1, 2, 3, 4, 5, 6, 7, 8), "seed"));
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> writers = new ArrayList<>();
        for (int w = 0; w < 4; w++) {
            int writer = w;
            Thread thread = new Thread(() -> {
                try (RespClient c = server.client()) {
                    Random random = new Random(writer);
                    for (int i = 0; i < 250; i++) {
                        assertEquals(":1", c.call("VADD", "v", "FP32", blob(randomVector(random, 8)), "w" + writer + ":" + i));
                    }
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
            writers.add(thread);
            thread.start();
        }
        for (Thread thread : writers) {
            thread.join();
        }
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
        try (RespClient c = server.client()) {
            assertEquals(":1001", c.call("VCARD", "v"));
            for (int w = 0; w < 4; w++) {
                assertEquals(":1", c.call("VISMEMBER", "v", "w" + w + ":249"));
            }
            // every element finds itself first: nothing was linked wrongly or lost
            Random random = new Random(2);
            for (int q = 0; q < 20; q++) {
                String element = "w" + random.nextInt(4) + ":" + random.nextInt(250);
                assertEquals(List.of(element), names(c.call("VSIM", "v", "ELE", element, "COUNT", "1")));
            }
        }
        assertEquals(java.util.List.of(), ((me.niteshh.redcake.vector.VectorSet) server.stack.store()
                .peek("v", me.niteshh.redcake.store.ValueType.VECTORSET)).validate());
    }

    @Test
    void manyClientsSearchWhileOthersInsertAndRemove() throws Exception {
        TestServer server = start(new TestServer());
        float[][] vectors;
        try (RespClient c = server.client()) {
            vectors = fill(c, "v", 800, 32, 13);
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try (RespClient c = server.client()) {
                Random random = new Random(3);
                for (int i = 0; i < 400; i++) {
                    c.call("VADD", "v", "FP32", blob(randomVector(random, 32)), "w:" + i);
                    if (i % 4 == 0) {
                        c.call("VREM", "v", "item:" + i);
                    }
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        List<Thread> readers = new ArrayList<>();
        for (int r = 0; r < 4; r++) {
            int seed = r;
            Thread reader = new Thread(() -> {
                try (RespClient c = server.client()) {
                    Random random = new Random(seed);
                    while (writer.isAlive()) {
                        String reply = c.call("VSIM", "v", "FP32", blob(vectors[random.nextInt(vectors.length)]),
                                "COUNT", "5", "WITHSCORES");
                        assertTrue(reply.startsWith("["), reply);
                        assertEquals(10, names(reply).size(), reply);
                    }
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
            readers.add(reader);
        }
        writer.start();
        readers.forEach(Thread::start);
        writer.join();
        for (Thread reader : readers) {
            reader.join();
        }
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
    }
}
