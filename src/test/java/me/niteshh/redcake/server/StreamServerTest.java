package me.niteshh.redcake.server;

import me.niteshh.redcake.config.AclConfig;
import me.niteshh.redcake.config.PersistenceConfig;
import me.niteshh.redcake.config.RedCakeAuthConfig;
import me.niteshh.redcake.config.RedCakeServerConfig;
import me.niteshh.redcake.replication.ReplicationConfig;
import me.niteshh.redcake.replication.ReplicationRole;
import me.niteshh.redcake.replication.replica.PrimaryConnection;
import me.niteshh.redcake.replication.replica.ReplicaSyncManager;
import me.niteshh.redcake.resp.ArrayValue;
import me.niteshh.redcake.resp.BulkString;
import me.niteshh.redcake.resp.RespValue;
import me.niteshh.redcake.security.AclRegistry;
import me.niteshh.redcake.stats.ReplicationStats;
import me.niteshh.redcake.store.KeyWaiters;
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
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Streams through a real server: a reader that waits and is woken, a client that leaves while it waits, many workers
 * sharing one group without ever getting the same entry twice, a worker that dies and whose entries are claimed by
 * another, roles, {@code maxmemory}, and the append-only file and replication carrying the groups along. Replies are
 * shown the way {@link RespClient} renders them.
 */
class StreamServerTest {

    @TempDir
    Path dir;

    private final List<TestServer> servers = new ArrayList<>();
    private final ExecutorService threads = Executors.newCachedThreadPool();
    private ReplicaSyncManager sync;
    private TestSupport.Stack replicaStack;

    @AfterEach
    void tearDown() {
        threads.shutdownNow();
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

    private static final Pattern ENTRY_ID = Pattern.compile("\"(\\d+-\\d+)\", \\[\"n\", \"(\\d+)\"");

    /** The {@code n} values of the entries in a rendered XREAD / XREADGROUP reply, in order. */
    private static List<Integer> numbers(String reply) {
        List<Integer> out = new ArrayList<>();
        Matcher matcher = ENTRY_ID.matcher(reply);
        while (matcher.find()) {
            out.add(Integer.parseInt(matcher.group(2)));
        }
        return out;
    }

    private static List<String> ids(String reply) {
        List<String> out = new ArrayList<>();
        Matcher matcher = ENTRY_ID.matcher(reply);
        while (matcher.find()) {
            out.add(matcher.group(1));
        }
        return out;
    }

    private static String[] withIds(List<String> head, List<String> ids) {
        List<String> all = new ArrayList<>(head);
        all.addAll(ids);
        return all.toArray(new String[0]);
    }

    private static String firstId(String reply) {
        Matcher matcher = Pattern.compile("\\[\\[\"(\\d+-\\d+)\"").matcher(reply);
        assertTrue(matcher.find(), "no entry in " + reply);
        return matcher.group(1);
    }

    /** Runs {@code call} on its own connection; the result arrives in the future. */
    private Future<String> async(TestServer server, String... command) {
        return threads.submit(() -> {
            try (RespClient client = server.client()) {
                client.setTimeout(20_000);
                return client.call(command);
            }
        });
    }

    private static void waitUntilBlocked(TestServer server, int clients) throws Exception {
        assertTrue(TestSupport.await(5_000, () -> server.stack.store().waiters().waiting() >= clients),
                "the client(s) did not start waiting");
    }

    // ----------------------------------------------------------------------------------- waiting

    @Test
    void aReaderWaitsForTheNextEntryAndIsWokenByIt() throws Exception {
        TestServer server = start(new TestServer());
        try (RespClient writer = server.client()) {
            Future<String> reader = async(server, "XREAD", "BLOCK", "10000", "STREAMS", "jobs", "$");
            waitUntilBlocked(server, 1);
            assertFalse(reader.isDone());
            assertTrue(writer.call("INFO", "clients").contains("blocked_clients:1"), "INFO shows who is waiting");
            long start = System.nanoTime();
            assertEquals("\"5-1\"", writer.call("XADD", "jobs", "5-1", "task", "build"));
            assertEquals("[[\"jobs\", [[\"5-1\", [\"task\", \"build\"]]]]]", reader.get(5, TimeUnit.SECONDS));
            assertTrue(System.nanoTime() - start < 2_000_000_000L, "woken at once, not at the timeout");
            assertTrue(TestSupport.await(2_000, () -> server.stack.store().waiters().waiting() == 0));
            assertTrue(writer.call("INFO", "clients").contains("blocked_clients:0"));
        }
    }

    @Test
    void aReaderThatFindsNothingGetsNilWhenTheTimeIsUp() throws Exception {
        TestServer server = start(new TestServer());
        try (RespClient client = server.client()) {
            client.setTimeout(10_000);
            long start = System.nanoTime();
            assertEquals("(nil)", client.call("XREAD", "BLOCK", "300", "STREAMS", "jobs", "$"));
            long millis = (System.nanoTime() - start) / 1_000_000;
            assertTrue(millis >= 290 && millis < 3_000, "waited about 300 ms: " + millis);
            assertEquals("+PONG", client.call("PING"), "the connection is fine afterwards");

            client.call("XADD", "jobs", "1-1", "a", "b");
            assertEquals("(nil)", client.call("XREAD", "BLOCK", "100", "STREAMS", "jobs", "1-1"), "nothing after 1-1");
            assertEquals("[[\"jobs\", [[\"1-1\", [\"a\", \"b\"]]]]]", client.call("XREAD", "BLOCK", "100", "STREAMS", "jobs", "0"),
                    "there is something: no waiting");
        }
    }

    @Test
    void everyPlainReaderGetsTheEntryButEachGroupMemberGetsADifferentOne() throws Exception {
        TestServer server = start(new TestServer());
        try (RespClient writer = server.client()) {
            writer.call("XGROUP", "CREATE", "jobs", "g", "$", "MKSTREAM");
            List<Future<String>> plain = List.of(
                    async(server, "XREAD", "BLOCK", "10000", "STREAMS", "jobs", "$"),
                    async(server, "XREAD", "BLOCK", "10000", "STREAMS", "jobs", "$"));
            List<Future<String>> workers = List.of(
                    async(server, "XREADGROUP", "GROUP", "g", "w1", "COUNT", "1", "BLOCK", "10000", "STREAMS", "jobs", ">"),
                    async(server, "XREADGROUP", "GROUP", "g", "w2", "COUNT", "1", "BLOCK", "10000", "STREAMS", "jobs", ">"),
                    async(server, "XREADGROUP", "GROUP", "g", "w3", "COUNT", "1", "BLOCK", "10000", "STREAMS", "jobs", ">"));
            waitUntilBlocked(server, 5);
            for (int i = 1; i <= 3; i++) {
                writer.call("XADD", "jobs", "10-" + i, "n", Integer.toString(i));
            }
            for (Future<String> reader : plain) {
                List<Integer> seen = numbers(reader.get(5, TimeUnit.SECONDS));
                assertEquals(1, seen.getFirst(), "a plain reader starts with the first entry, whoever else reads it: " + seen);
            }
            Set<Integer> given = new java.util.TreeSet<>();
            for (Future<String> worker : workers) {
                List<Integer> mine = numbers(worker.get(5, TimeUnit.SECONDS));
                assertEquals(1, mine.size(), "COUNT 1");
                assertTrue(given.add(mine.getFirst()), "nobody gets an entry twice: " + mine);
            }
            assertEquals(Set.of(1, 2, 3), given);
            assertEquals("[:3, \"10-1\", \"10-3\", [[\"w1\", \"1\"], [\"w2\", \"1\"], [\"w3\", \"1\"]]]", writer.call("XPENDING", "jobs", "g"));
        }
    }

    @Test
    void anEntryThatArrivesJustAfterTheCommandIsNeverMissed() throws Exception {
        TestServer server = start(new TestServer());
        try (RespClient writer = server.client()) {
            for (int round = 0; round < 60; round++) {
                String key = "q" + round;
                Future<String> reader = async(server, "XREAD", "BLOCK", "10000", "STREAMS", key, "$");
                // the entries start coming the moment the reader sends its command; the first one the reader
                // can see is the first after its "$", and whichever it is, it must arrive
                long deadline = System.nanoTime() + 8_000_000_000L;
                int n = 0;
                while (!reader.isDone() && System.nanoTime() < deadline) {
                    writer.call("XADD", key, "*", "n", Integer.toString(n++));
                }
                assertTrue(reader.isDone(), "round " + round + ": the reader was left waiting although entries kept coming");
                assertTrue(reader.get().contains("[\"n\", \""), reader.get());
            }
        }
    }

    @Test
    void aClientThatHangsUpWhileItWaitsIsNotHandedAnythingLater() throws Exception {
        TestServer server = start(new TestServer());
        try (RespClient admin = server.client()) {
            admin.call("XGROUP", "CREATE", "jobs", "g", "$", "MKSTREAM");
            RespClient ghost = server.client();
            ghost.send("XREADGROUP", "GROUP", "g", "ghost", "BLOCK", "0", "STREAMS", "jobs", ">");
            waitUntilBlocked(server, 1);
            ghost.close(); // the worker died
            assertTrue(TestSupport.await(5_000, () -> server.stack.store().waiters().waiting() == 0),
                    "the server noticed that the client is gone");
            admin.call("XADD", "jobs", "1-1", "n", "1");
            assertEquals("[:0, (nil), (nil), (nil)]", admin.call("XPENDING", "jobs", "g"), "the entry was not given to a dead client");
            assertEquals("[[\"jobs\", [[\"1-1\", [\"n\", \"1\"]]]]]",
                    admin.call("XREADGROUP", "GROUP", "g", "alive", "STREAMS", "jobs", ">"), "it is still there for a live one");
        }
    }

    @Test
    void killingAWaitingClientEndsItsWait() throws Exception {
        TestServer server = start(new TestServer());
        try (RespClient admin = server.client(); RespClient waiting = server.client()) {
            String id = waiting.call("CLIENT", "ID").substring(1);
            waiting.send("XREAD", "BLOCK", "0", "STREAMS", "jobs", "$");
            waitUntilBlocked(server, 1);
            assertEquals(":1", admin.call("CLIENT", "KILL", "ID", id));
            assertTrue(TestSupport.await(5_000, () -> server.stack.store().waiters().waiting() == 0),
                    "the killed client is no longer waited for");
            assertNull(waiting.read(), "its connection was closed");
        }
    }

    @Test
    void anIdleTimeoutDoesNotCutAConnectionThatIsWaiting() throws Exception {
        TestServer server = start(new TestServer().withIdleTimeout(1));
        try (RespClient client = server.client()) {
            client.setTimeout(10_000);
            assertEquals("(nil)", client.call("XREAD", "BLOCK", "2500", "STREAMS", "jobs", "$"),
                    "waited for 2.5 s although idle clients are dropped after 1 s");
            assertEquals("+PONG", client.call("PING"));
        }
    }

    @Test
    void stoppingTheServerEndsEveryWait() throws Exception {
        TestServer server = start(new TestServer());
        List<RespClient> waiting = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            RespClient client = server.client();
            client.send("XREAD", "BLOCK", "0", "STREAMS", "jobs", "$");
            waiting.add(client);
        }
        waitUntilBlocked(server, 5);
        long start = System.nanoTime();
        server.close();
        servers.remove(server);
        assertTrue(System.nanoTime() - start < 5_000_000_000L, "the server stops although clients wait for ever");
        for (RespClient client : waiting) {
            client.close();
        }
    }

    @Test
    void deletingTheStreamEndsAGroupReadersWaitWithAnError() throws Exception {
        TestServer server = start(new TestServer());
        try (RespClient admin = server.client()) {
            admin.call("XGROUP", "CREATE", "jobs", "g", "$", "MKSTREAM");
            Future<String> reader = async(server, "XREADGROUP", "GROUP", "g", "w", "BLOCK", "0", "STREAMS", "jobs", ">");
            waitUntilBlocked(server, 1);
            admin.call("DEL", "jobs");
            assertEquals("-NOGROUP No such key 'jobs' or consumer group 'g' in XREADGROUP with GROUP option",
                    reader.get(5, TimeUnit.SECONDS));

            admin.call("XGROUP", "CREATE", "jobs", "g", "$", "MKSTREAM");
            Future<String> second = async(server, "XREADGROUP", "GROUP", "g", "w", "BLOCK", "0", "STREAMS", "jobs", ">");
            waitUntilBlocked(server, 1);
            assertEquals(":1", admin.call("XGROUP", "DESTROY", "jobs", "g"));
            assertTrue(second.get(5, TimeUnit.SECONDS).startsWith("-NOGROUP"));
        }
    }

    @Test
    void setBackTheGroupAndTheWaitingReaderGetsTheEntriesAgain() throws Exception {
        TestServer server = start(new TestServer());
        try (RespClient admin = server.client()) {
            admin.call("XADD", "jobs", "1-1", "n", "1");
            admin.call("XGROUP", "CREATE", "jobs", "g", "$");
            Future<String> reader = async(server, "XREADGROUP", "GROUP", "g", "w", "BLOCK", "0", "STREAMS", "jobs", ">");
            waitUntilBlocked(server, 1);
            assertEquals("+OK", admin.call("XGROUP", "SETID", "jobs", "g", "0"));
            assertEquals(List.of(1), numbers(reader.get(5, TimeUnit.SECONDS)));
        }
    }

    @Test
    void insideMultiNothingWaits() throws Exception {
        TestServer server = start(new TestServer());
        try (RespClient client = server.client()) {
            client.setTimeout(10_000);
            client.call("XGROUP", "CREATE", "jobs", "g", "$", "MKSTREAM");
            assertEquals("+OK", client.call("MULTI"));
            assertEquals("+QUEUED", client.call("XREAD", "BLOCK", "60000", "STREAMS", "jobs", "$"));
            assertEquals("+QUEUED", client.call("XREADGROUP", "GROUP", "g", "w", "BLOCK", "0", "STREAMS", "jobs", ">"));
            assertEquals("+QUEUED", client.call("XADD", "jobs", "1-1", "n", "1"));
            long start = System.nanoTime();
            assertEquals("[(nil), (nil), \"1-1\"]", client.call("EXEC"));
            assertTrue(System.nanoTime() - start < 2_000_000_000L);
        }
    }

    // ----------------------------------------------------------------------- the job queue

    @Test
    void manyWorkersShareOneGroupAndEveryEntryIsProcessedExactlyOnce() throws Exception {
        TestServer server = start(new TestServer());
        int total = 600;
        int workerCount = 6;
        try (RespClient admin = server.client()) {
            admin.call("XGROUP", "CREATE", "jobs", "g", "0", "MKSTREAM");
        }
        Map<Integer, AtomicInteger> processed = new ConcurrentHashMap<>();
        AtomicInteger done = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> all = new ArrayList<>();
        for (int w = 0; w < workerCount; w++) {
            String name = "w" + w;
            all.add(threads.submit(() -> {
                try (RespClient client = server.client()) {
                    client.setTimeout(20_000);
                    go.await();
                    while (done.get() < total) {
                        String reply = client.call("XREADGROUP", "GROUP", "g", name, "COUNT", "4", "BLOCK", "200",
                                "STREAMS", "jobs", ">");
                        List<Integer> mine = numbers(reply);
                        for (int n : mine) {
                            processed.computeIfAbsent(n, k -> new AtomicInteger()).incrementAndGet();
                        }
                        List<String> got = ids(reply);
                        if (!got.isEmpty()) {
                            List<String> ack = new ArrayList<>(List.of("XACK", "jobs", "g"));
                            ack.addAll(got);
                            assertEquals(":" + got.size(), client.call(ack.toArray(new String[0])));
                            done.addAndGet(got.size());
                        }
                    }
                }
                return null;
            }));
        }
        all.add(threads.submit(() -> {
            try (RespClient producer = server.client()) {
                Random random = new Random(7);
                go.await();
                for (int n = 1; n <= total; n++) {
                    producer.call("XADD", "jobs", "*", "n", Integer.toString(n));
                    if (random.nextInt(20) == 0) {
                        Thread.sleep(random.nextInt(5));
                    }
                }
            }
            return null;
        }));
        go.countDown();
        for (Future<?> future : all) {
            future.get(60, TimeUnit.SECONDS);
        }

        assertEquals(total, processed.size(), "every entry was processed");
        processed.forEach((n, times) -> assertEquals(1, times.get(), "entry " + n + " was handed out " + times.get() + " times"));
        try (RespClient admin = server.client()) {
            assertEquals("[:0, (nil), (nil), (nil)]", admin.call("XPENDING", "jobs", "g"));
            String groups = admin.call("XINFO", "GROUPS", "jobs");
            assertTrue(groups.contains("\"entries-read\", :" + total) && groups.contains("\"lag\", :0"), groups);
        }
    }

    @Test
    void whatAWorkerThatDiedWasDoingIsClaimedByAnotherAndFinished() throws Exception {
        TestServer server = start(new TestServer());
        try (RespClient producer = server.client(); RespClient doomed = server.client(); RespClient rescuer = server.client()) {
            producer.call("XGROUP", "CREATE", "jobs", "g", "0", "MKSTREAM");
            for (int n = 1; n <= 5; n++) {
                producer.call("XADD", "jobs", "*", "n", Integer.toString(n));
            }
            String taken = doomed.call("XREADGROUP", "GROUP", "g", "doomed", "COUNT", "3", "STREAMS", "jobs", ">");
            assertEquals(List.of(1, 2, 3), numbers(taken));
            List<String> doomedIds = ids(taken);
            doomed.close(); // dies without acknowledging
            Thread.sleep(400);

            assertEquals(List.of(4, 5), numbers(rescuer.call("XREADGROUP", "GROUP", "g", "rescuer", "STREAMS", "jobs", ">")));
            String claimed = rescuer.call("XAUTOCLAIM", "jobs", "g", "rescuer", "300", "0");
            assertEquals(List.of(1, 2, 3), numbers(claimed), "what had been pending for 300 ms or more, and only that");
            assertTrue(claimed.startsWith("[\"0-0\", "), "the whole list was looked at");

            List<String> ack = new ArrayList<>(List.of("XACK", "jobs", "g"));
            ack.addAll(doomedIds);
            assertEquals(":3", rescuer.call(ack.toArray(new String[0])));
            assertTrue(rescuer.call("XPENDING", "jobs", "g").startsWith("[:2, "), "the two it read itself are still pending");
            assertEquals(List.of(4, 5), numbers(rescuer.call("XREADGROUP", "GROUP", "g", "rescuer", "STREAMS", "jobs", "0")),
                    "a restarted worker finds its own unfinished work");
        }
    }

    // ------------------------------------------------------------------------- roles, memory

    @Test
    void aReadOnlyUserCanLookButNotTakeWorkOrChangeAnything() throws Exception {
        Path file = dir.resolve("users.acl");
        Files.writeString(file, "user bob readwrite %s\nuser carol readonly %s\n".formatted(
                AclRegistry.hash("b".getBytes(StandardCharsets.UTF_8)), AclRegistry.hash("c".getBytes(StandardCharsets.UTF_8))));
        TestServer server = start(new TestServer().withAcl(new AclRegistry(new AclConfig(file))));
        try (RespClient bob = server.client()) {
            assertEquals("+OK", bob.call("AUTH", "bob", "b"));
            bob.call("XADD", "jobs", "1-1", "n", "1");
            assertEquals("+OK", bob.call("XGROUP", "CREATE", "jobs", "g", "0"));
            assertEquals(List.of(1), numbers(bob.call("XREADGROUP", "GROUP", "g", "w", "STREAMS", "jobs", ">")));
            assertTrue(bob.call("XRESTORE", "jobs", "g", "w", "1-1", "1", "1").startsWith("-NOPERM"), "XRESTORE is for snapshots: admin only");
        }
        try (RespClient carol = server.client()) {
            assertEquals("+OK", carol.call("AUTH", "carol", "c"));
            assertEquals(":1", carol.call("XLEN", "jobs"));
            assertEquals(List.of(1), numbers(carol.call("XRANGE", "jobs", "-", "+")));
            assertEquals(List.of(1), numbers(carol.call("XREAD", "STREAMS", "jobs", "0")));
            assertEquals("(nil)", carol.call("XREAD", "BLOCK", "50", "STREAMS", "jobs", "$"), "waiting is a read");
            assertTrue(carol.call("XPENDING", "jobs", "g").startsWith("[:1, "));
            assertTrue(carol.call("XINFO", "STREAM", "jobs").startsWith("[\"length\", :1"));
            assertTrue(carol.call("XINFO", "GROUPS", "jobs").contains("\"name\", \"g\""));
            for (String[] write : new String[][]{
                    {"XADD", "jobs", "*", "a", "b"}, {"XDEL", "jobs", "1-1"}, {"XTRIM", "jobs", "MAXLEN", "0"},
                    {"XSETID", "jobs", "9-9"}, {"XGROUP", "CREATE", "jobs", "g2", "0"}, {"XACK", "jobs", "g", "1-1"},
                    {"XREADGROUP", "GROUP", "g", "c", "STREAMS", "jobs", ">"}, {"XCLAIM", "jobs", "g", "c", "0", "1-1"},
                    {"XAUTOCLAIM", "jobs", "g", "c", "0", "0"}, {"XRESTORE", "jobs", "g", "c", "1-1", "1", "1"}}) {
                assertTrue(carol.call(write).startsWith("-NOPERM"), String.join(" ", write));
            }
            assertEquals(":1", carol.call("XLEN", "jobs"));
        }
    }

    @Test
    void streamsCountTowardMaxmemoryAndTrimmingGivesTheSpaceBack() throws Exception {
        TestServer server = start(new TestServer());
        try (RespClient client = server.client()) {
            assertEquals("+OK", client.call("CONFIG", "SET", "maxmemory", "200000"));
            int added = 0;
            String reply = "";
            while (added < 2000) {
                reply = client.call("XADD", "log", "*", "payload", "x".repeat(200));
                if (reply.startsWith("-")) {
                    break;
                }
                added++;
            }
            assertTrue(reply.startsWith("-OOM"), "the limit was reached after " + added + " entries: " + reply);
            assertTrue(added > 100 && added < 1500, "added " + added);
            assertTrue(client.call("XGROUP", "CREATE", "other", "g", "0", "MKSTREAM").startsWith("-OOM"),
                    "a command that makes more is refused");
            String oldest = firstId(client.call("XRANGE", "log", "-", "+", "COUNT", "1"));
            assertEquals(":1", client.call("XDEL", "log", oldest), "deleting is allowed when memory is full");
            assertEquals(":" + (added - 11), client.call("XTRIM", "log", "MAXLEN", "10"), "and so is trimming");
            assertTrue(client.call("XADD", "log", "*", "payload", "y").startsWith("\""), "and then there is room again");
        }
    }

    // ----------------------------------------------------------------------- AOF and replication

    private static String state(RespClient client, String key) throws Exception {
        String full = client.call("XINFO", "STREAM", key, "FULL", "COUNT", "0");
        return full.replaceAll("(\"(?:seen-time|active-time|radix-tree-keys|radix-tree-nodes)\", :)-?\\d+", "$1_");
    }

    private void buildStreamWithGroups(RespClient c) throws Exception {
        for (int n = 1; n <= 40; n++) {
            c.call("XADD", "jobs", "*", "n", Integer.toString(n), "text", "payload " + n);
        }
        c.call("XGROUP", "CREATE", "jobs", "g", "0");
        c.call("XGROUP", "CREATE", "jobs", "tail", "$");
        c.call("XREADGROUP", "GROUP", "g", "w1", "COUNT", "10", "STREAMS", "jobs", ">");
        c.call("XREADGROUP", "GROUP", "g", "w2", "COUNT", "10", "STREAMS", "jobs", ">");
        c.call("XREADGROUP", "GROUP", "g", "w3", "NOACK", "COUNT", "3", "STREAMS", "jobs", ">");
        c.call(withIds(List.of("XACK", "jobs", "g"), ids(c.call("XRANGE", "jobs", "-", "+", "COUNT", "4"))));
        c.call(withIds(List.of("XDEL", "jobs"), ids(c.call("XREVRANGE", "jobs", "+", "-", "COUNT", "1"))));
        c.call("XDEL", "jobs", ids(c.call("XRANGE", "jobs", "-", "+", "COUNT", "6")).get(5)); // pending for w1, message gone
        c.call("XAUTOCLAIM", "jobs", "g", "w3", "0", "0", "COUNT", "5");
    }

    @Test
    void streamsAndTheirGroupsSurviveARestartAndARewriteOfTheAppendOnlyFile() throws Exception {
        Path file = dir.resolve("streams.aof");
        PersistenceConfig persistence = new PersistenceConfig(file, PersistenceConfig.Fsync.ALWAYS);
        String before;
        try (TestServer first = new TestServer().withAof(persistence).start(); RespClient c = first.client()) {
            buildStreamWithGroups(c);
            c.call("XADD", "other", "7-7", "a", "b");
            c.call("XGROUP", "CREATE", "empty", "g", "$", "MKSTREAM");
            c.call("EXPIRE", "other", "100000");
            before = state(c, "jobs");
            assertTrue(before.contains("\"pel-count\", :"), before);
        }
        try (TestServer second = new TestServer().withAof(persistence).start(); RespClient c = second.client()) {
            assertEquals(before, state(c, "jobs"), "replaying the log: the ids XADD chose, the deliveries, the claims");
            assertEquals("[[\"7-7\", [\"a\", \"b\"]]]", c.call("XRANGE", "other", "-", "+"));
            long ttl = Long.parseLong(c.call("TTL", "other").substring(1));
            assertTrue(ttl > 90_000 && ttl <= 100_000, "the expiry survives: " + ttl);
            assertTrue(c.call("BGREWRITEAOF").startsWith("+"));
        }
        try (TestServer third = new TestServer().withAof(persistence).start(); RespClient c = third.client()) {
            assertEquals(before, state(c, "jobs"), "after the log was rewritten from the data");
            assertTrue(c.call("XINFO", "GROUPS", "empty").contains("\"name\", \"g\""), "an empty stream with a group is kept");
            assertEquals("+stream", c.call("TYPE", "empty"));
            c.call("XADD", "jobs", "*", "n", "after");
            assertTrue(Files.size(file) > 0);
        }
    }

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

    private String onReplica(String... command) {
        RespValue reply = TestSupport.allCommands(replicaStack.store(), ReplicationRole.REPLICA).handle(List.of(command));
        return render(reply);
    }

    private static String render(RespValue value) {
        if (value instanceof ArrayValue array) {
            return array.elements().stream().map(StreamServerTest::render).toList().toString();
        }
        if (value instanceof BulkString bulk) {
            return "\"" + bulk.value() + "\"";
        }
        if (value instanceof me.niteshh.redcake.resp.IntegerValue number) {
            return ":" + number.value();
        }
        if (value instanceof me.niteshh.redcake.resp.NullValue || value instanceof me.niteshh.redcake.resp.NullArray) {
            return "(nil)";
        }
        return String.valueOf(value);
    }

    @Test
    void aReplicaFollowsAStreamItsGroupsAndWhoIsWorkingOnWhat() throws Exception {
        TestServer primary = start(new TestServer());
        try (RespClient c = primary.client()) {
            buildStreamWithGroups(c); // exists before the replica joins: travels in the full sync
            startReplicaOf(primary);
            assertTrue(TestSupport.await(15_000, () -> replicaStack.store().exists("jobs")), "the full sync did not arrive");
            String want = state(c, "jobs");
            assertTrue(TestSupport.await(5_000, () -> normalize(onReplica("XINFO", "STREAM", "jobs", "FULL", "COUNT", "0")).equals(want)),
                    "after the full sync");

            for (int n = 100; n < 130; n++) { // and live
                c.call("XADD", "jobs", "*", "n", Integer.toString(n));
            }
            c.call("XREADGROUP", "GROUP", "g", "w4", "COUNT", "7", "STREAMS", "jobs", ">");
            c.call("XCLAIM", "jobs", "g", "w5", "0", firstId(c.call("XPENDING", "jobs", "g", "-", "+", "1")));
            c.call("XGROUP", "DELCONSUMER", "jobs", "g", "w2");
            c.call("XTRIM", "jobs", "MAXLEN", "50");
            String live = state(c, "jobs");
            assertTrue(TestSupport.await(10_000, () -> normalize(onReplica("XINFO", "STREAM", "jobs", "FULL", "COUNT", "0")).equals(live)),
                    "the replica did not reach the primary's state");
        }
    }

    private static String normalize(String full) {
        return full.replaceAll("(\"(?:seen-time|active-time|radix-tree-keys|radix-tree-nodes)\", :)-?\\d+", "$1_");
    }

    @Test
    void aReplicaRefusesToTakeWork() throws Exception {
        TestServer replica = start(new TestServer().withRole(ReplicationRole.REPLICA));
        try (RespClient c = replica.client()) {
            assertTrue(c.call("XREADGROUP", "GROUP", "g", "w", "STREAMS", "jobs", ">").startsWith("-READONLY"));
            assertTrue(c.call("XADD", "jobs", "*", "a", "b").startsWith("-READONLY"));
            assertTrue(c.call("XACK", "jobs", "g", "1-1").startsWith("-READONLY"));
            assertEquals("(nil)", c.call("XREAD", "BLOCK", "50", "STREAMS", "jobs", "$"), "but it can read, and wait");
        }
    }

    @Test
    void whatReplicationAppliesWakesTheReadersWaitingOnAReplica() throws Exception {
        TestServer primary = start(new TestServer());
        startReplicaOf(primary);
        KeyWaiters waiters = replicaStack.store().waiters();
        AtomicReference<String> seen = new AtomicReference<>();
        CountDownLatch waiting = new CountDownLatch(1);
        Future<?> reader = threads.submit(() -> {
            try (KeyWaiters.Registration registration = waiters.register(List.of("jobs"))) {
                long[] versions = registration.versions();
                waiting.countDown();
                assertTrue(registration.await(versions, System.nanoTime() + 10_000_000_000L, 100_000_000L, () -> true),
                        "woken by the entry the primary sent");
                seen.set(onReplica("XRANGE", "jobs", "-", "+"));
            }
        });
        assertTrue(waiting.await(5, TimeUnit.SECONDS));
        try (RespClient c = primary.client()) {
            c.call("XADD", "jobs", "3-1", "n", "1");
        }
        reader.get(10, TimeUnit.SECONDS);
        assertEquals("[[\"3-1\", [\"n\", \"1\"]]]", seen.get());
    }
}
