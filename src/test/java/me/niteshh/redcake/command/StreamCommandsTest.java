package me.niteshh.redcake.command;

import me.niteshh.redcake.replication.ReplicationRole;
import me.niteshh.redcake.resp.ArrayValue;
import me.niteshh.redcake.resp.BulkString;
import me.niteshh.redcake.resp.ErrorValue;
import me.niteshh.redcake.resp.IntegerValue;
import me.niteshh.redcake.resp.NullArray;
import me.niteshh.redcake.resp.NullValue;
import me.niteshh.redcake.resp.RespValue;
import me.niteshh.redcake.resp.SimpleString;
import me.niteshh.redcake.store.SnapshotEntry;
import me.niteshh.redcake.support.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The stream commands without a socket: what they log and replicate (the id {@code XADD} chose, the result of
 * {@code XREADGROUP}), and that a replica which replays that, or a node rebuilt from a snapshot, ends up with
 * exactly the same stream and groups. Replies in detail are checked against recorded Redis output in
 * {@code StreamGoldenTest}.
 */
class StreamCommandsTest {

    private TestSupport.Stack stack;
    private CommandHandler handler;

    @BeforeEach
    void setUp() {
        stack = TestSupport.newStack();
        handler = TestSupport.allCommands(stack.store(), ReplicationRole.PRIMARY);
    }

    @AfterEach
    void tearDown() {
        stack.stop();
    }

    private RespValue run(String... command) {
        return handler.handle(List.of(command));
    }

    /** Runs a command the way the server runs a write on a primary: normalize, execute, and see what is to be logged. */
    private record Logged(RespValue reply, List<List<String>> commands) {
    }

    private static Logged primaryRun(CommandHandler handler, long now, String... command) {
        List<String> normalized = handler.normalize(List.of(command), now);
        Propagation.begin();
        RespValue reply;
        List<List<String>> effects;
        try {
            reply = handler.handle(normalized);
        } finally {
            effects = Propagation.end();
        }
        List<List<String>> logged = effects != null ? effects
                : reply instanceof ErrorValue ? List.of() : List.of(normalized);
        return new Logged(reply, logged);
    }

    private Logged logged(String... command) {
        return primaryRun(handler, System.currentTimeMillis(), command);
    }

    // ------------------------------------------------------------------------------ rendering

    private static String render(RespValue value) {
        return switch (value) {
            case BulkString b -> "\"" + b.value() + "\"";
            case IntegerValue i -> ":" + i.value();
            case SimpleString s -> "+" + s.value();
            case ErrorValue e -> "-" + e.message();
            case NullValue ignored -> "(nil)";
            case NullArray ignored -> "(nilarray)";
            case ArrayValue a -> a.elements().stream().map(StreamCommandsTest::render).toList().toString();
        };
    }

    private String show(String... command) {
        return render(run(command));
    }

    private static final Pattern NOT_REPLICATED = Pattern.compile(
            "(\"(?:seen-time|active-time|radix-tree-keys|radix-tree-nodes)\", :)-?\\d+");

    /**
     * Everything a client can see of a stream and its groups, except what is not part of the replicated state: when a
     * consumer was last seen or active, and how the entries happen to be laid out in blocks.
     */
    private static String dump(CommandHandler handler, String key) {
        RespValue full = handler.handle(List.of("XINFO", "STREAM", key, "FULL", "COUNT", "0"));
        return NOT_REPLICATED.matcher(render(full)).replaceAll("$1_");
    }

    // ------------------------------------------------------------------------- what is logged

    @Test
    void xaddIsLoggedWithTheIdItGot() {
        long now = 1_700_000_000_000L;
        Logged first = primaryRun(handler, now, "XADD", "k", "*", "a", "1");
        assertEquals("\"" + now + "-0\"", render(first.reply()));
        assertEquals(List.of(List.of("XADD", "k", now + "-0", "a", "1")), first.commands());

        Logged same = primaryRun(handler, now, "XADD", "k", "*", "a", "2");
        assertEquals(List.of(List.of("XADD", "k", now + "-1", "a", "2")), same.commands(), "same millisecond: next sequence");

        Logged back = primaryRun(handler, now - 5000, "XADD", "k", "*", "a", "3");
        assertEquals(List.of(List.of("XADD", "k", now + "-2", "a", "3")), back.commands(), "the clock went back");

        Logged auto = primaryRun(handler, now + 10, "XADD", "k", "MAXLEN", "~", "100", "NOMKSTREAM", "*", "a", "4");
        assertEquals(List.of("XADD", "k", "MAXLEN", "~", "100", "NOMKSTREAM", (now + 10) + "-0", "a", "4"), auto.commands().getFirst(),
                "only the id is replaced");

        Logged milliseconds = primaryRun(handler, now, "XADD", "k", (now + 10) + "-*", "a", "5");
        assertEquals(List.of(List.of("XADD", "k", (now + 10) + "-1", "a", "5")), milliseconds.commands());

        Logged explicit = primaryRun(handler, now, "XADD", "k", (now + 99) + "-7", "a", "6");
        assertEquals(List.of("XADD", "k", (now + 99) + "-7", "a", "6"), explicit.commands().getFirst(), "an explicit id stays");
    }

    @Test
    void aRefusedXaddLogsNothingAndTheNormalizationNeverThrows() {
        assertEquals(1, logged("XADD", "k", "1-1", "a", "1").commands().size(), "an accepted XADD is logged");
        Logged refused = logged("XADD", "k", "1-1", "a", "1");
        assertInstanceOf(ErrorValue.class, refused.reply());
        assertTrue(refused.commands().isEmpty());

        run("SET", "string", "v");
        for (String[] bad : new String[][]{
                {"XADD", "string", "*", "a", "1"}, {"XADD", "k"}, {"XADD", "k", "*"}, {"XADD", "k", "maxlen"},
                {"XADD", "k", "abc", "a", "1"}, {"XADD", "k", "MAXLEN", "x", "*", "a", "1"}}) {
            assertDoesNotThrow(() -> handler.normalize(List.of(bad), 1L), String.join(" ", bad));
            assertInstanceOf(ErrorValue.class, run(bad), String.join(" ", bad));
        }
    }

    @Test
    void xreadgroupLogsWhatItDidAndNotItself() {
        run("XADD", "q", "1-1", "job", "a");
        run("XADD", "q", "2-1", "job", "b");
        run("XADD", "q", "3-1", "job", "c");
        run("XGROUP", "CREATE", "q", "g", "0");

        Logged two = logged("XREADGROUP", "GROUP", "g", "w1", "COUNT", "2", "STREAMS", "q", ">");
        assertEquals("[[\"q\", [[\"1-1\", [\"job\", \"a\"]], [\"2-1\", [\"job\", \"b\"]]]]]", render(two.reply()));
        List<List<String>> commands = two.commands();
        assertEquals(List.of("XGROUP", "CREATECONSUMER", "q", "g", "w1"), commands.get(0), "the consumer is made first");
        assertEquals(List.of("XCLAIM", "q", "g", "w1", "0", "1-1", "TIME", commands.get(1).get(7), "RETRYCOUNT", "1",
                "FORCE", "JUSTID", "LASTID", "1-1"), commands.get(1));
        assertEquals("2-1", commands.get(2).get(5));
        assertEquals("2-1", commands.get(2).getLast(), "each entry carries the group position after it");
        assertEquals(List.of("XGROUP", "SETID", "q", "g", "2-1", "ENTRIESREAD", "2"), commands.get(3));
        assertEquals(4, commands.size());

        Logged nothing = logged("XREADGROUP", "GROUP", "g", "w1", "STREAMS", "q", "0");
        assertEquals(2, nothing.commands().size(), "history: each entry's new time and count, as XCLAIM ... JUSTID");
        assertTrue(nothing.commands().getFirst().containsAll(List.of("JUSTID", "RETRYCOUNT", "2")));
        assertFalse(nothing.commands().getFirst().contains("FORCE"));

        Logged noAck = logged("XREADGROUP", "GROUP", "g", "w2", "NOACK", "STREAMS", "q", ">");
        assertEquals(List.of(List.of("XGROUP", "CREATECONSUMER", "q", "g", "w2"),
                List.of("XGROUP", "SETID", "q", "g", "3-1", "ENTRIESREAD", "3")), noAck.commands(),
                "NOACK: no pending entry, only the new position");

        Logged empty = logged("XREADGROUP", "GROUP", "g", "w2", "STREAMS", "q", ">");
        assertEquals("(nilarray)", render(empty.reply()));
        assertTrue(empty.commands().isEmpty(), "nothing happened, nothing is logged");

        Logged error = logged("XREADGROUP", "GROUP", "nogroup", "w2", "STREAMS", "q", ">");
        assertInstanceOf(ErrorValue.class, error.reply());
        assertTrue(error.commands().isEmpty());
    }

    @Test
    void xclaimAndXautoclaimLogWhatTheyDid() {
        run("XADD", "q", "1-1", "job", "a");
        run("XADD", "q", "2-1", "job", "b");
        run("XGROUP", "CREATE", "q", "g", "0");
        logged("XREADGROUP", "GROUP", "g", "w1", "STREAMS", "q", ">");

        Logged claim = logged("XCLAIM", "q", "g", "w2", "0", "1-1", "9-9", "IDLE", "500");
        assertEquals("[[\"1-1\", [\"job\", \"a\"]]]", render(claim.reply()));
        assertEquals(List.of("XGROUP", "CREATECONSUMER", "q", "g", "w2"), claim.commands().get(0));
        List<String> xclaim = claim.commands().get(1);
        assertEquals("XCLAIM", xclaim.get(0));
        assertEquals("1-1", xclaim.get(5));
        assertTrue(xclaim.containsAll(List.of("FORCE", "JUSTID", "RETRYCOUNT", "2")), xclaim.toString());
        assertEquals(2, claim.commands().size());

        run("XDEL", "q", "2-1");
        Logged auto = logged("XAUTOCLAIM", "q", "g", "w2", "0", "0");
        assertEquals("[\"0-0\", [[\"1-1\", [\"job\", \"a\"]]], [\"2-1\"]]", render(auto.reply()),
                "min-idle 0 claims what is there again, and reports the pending entry of the deleted message");
        assertTrue(auto.commands().contains(List.of("XACK", "q", "g", "2-1")), auto.commands().toString());

        Logged none = logged("XCLAIM", "q", "g", "w2", "99999999", "1-1");
        assertTrue(none.commands().isEmpty(), "nothing claimed, nothing created, nothing logged");
    }

    // --------------------------------------------------------------------- replay and snapshots

    /** One more node that only ever sees what the primary logs, as a replica or an AOF replay does. */
    private static final class Follower implements AutoCloseable {
        final TestSupport.Stack stack = TestSupport.newStack();
        final CommandHandler handler = TestSupport.allCommands(stack.store(), ReplicationRole.PRIMARY);

        void apply(List<List<String>> commands) {
            for (List<String> command : commands) {
                RespValue reply = handler.handle(command);
                assertFalse(reply instanceof ErrorValue, "the follower refused " + command + ": " + reply);
            }
        }

        @Override
        public void close() {
            stack.stop();
        }
    }

    private static void randomWorkload(CommandHandler primary, Follower follower, Random random, int steps, String key) {
        long clock = 1_700_000_000_000L;
        String group = "g";
        primaryRun(primary, clock, "XGROUP", "CREATE", key, group, "0", "MKSTREAM");
        follower.apply(List.of(List.of("XGROUP", "CREATE", key, group, "0", "MKSTREAM")));
        for (int step = 0; step < steps; step++) {
            clock += random.nextInt(40);
            String worker = "w" + random.nextInt(4);
            int op = random.nextInt(100);
            String[] command;
            if (op < 30) {
                command = new String[]{"XADD", key, "*", "n", Integer.toString(step), "p", "x".repeat(random.nextInt(30))};
            } else if (op < 48) {
                command = new String[]{"XREADGROUP", "GROUP", group, worker, "COUNT", Integer.toString(1 + random.nextInt(4)), "STREAMS", key, ">"};
            } else if (op < 53) {
                command = new String[]{"XREADGROUP", "GROUP", group, worker, "NOACK", "STREAMS", key, ">"};
            } else if (op < 58) {
                command = new String[]{"XREADGROUP", "GROUP", group, worker, "STREAMS", key, "0"};
            } else if (op < 70) {
                RespValue pending = primary.handle(List.of("XPENDING", key, group, "-", "+", "4"));
                List<RespValue> rows = ((ArrayValue) pending).elements();
                if (rows.isEmpty()) {
                    continue;
                }
                List<String> ack = new ArrayList<>(List.of("XACK", key, group));
                for (RespValue row : rows.subList(0, 1 + random.nextInt(rows.size()))) {
                    ack.add(((BulkString) ((ArrayValue) row).elements().get(0)).value());
                }
                command = ack.toArray(new String[0]);
            } else if (op < 78) {
                command = new String[]{"XAUTOCLAIM", key, group, worker, "0", "0", "COUNT", "2"};
            } else if (op < 84) {
                command = new String[]{"XCLAIM", key, group, worker, "0", (1 + random.nextInt(60)) + "-0", "1793557236000-0", "JUSTID"};
            } else if (op < 90) {
                RespValue some = primary.handle(List.of("XRANGE", key, "-", "+", "COUNT", "3"));
                List<RespValue> rows = ((ArrayValue) some).elements();
                if (rows.isEmpty()) {
                    continue;
                }
                command = new String[]{"XDEL", key, ((BulkString) ((ArrayValue) rows.get(random.nextInt(rows.size()))).elements().get(0)).value()};
            } else if (op < 93) {
                command = new String[]{"XTRIM", key, "MAXLEN", Integer.toString(2 + random.nextInt(10))};
            } else if (op < 95) {
                command = new String[]{"XGROUP", "DELCONSUMER", key, group, worker};
            } else if (op < 97) {
                command = new String[]{"XGROUP", "SETID", key, group, random.nextBoolean() ? "0" : "$"};
            } else if (op < 98) {
                command = new String[]{"XSETID", key, "9999999999999999-0"};
            } else {
                command = new String[]{"XGROUP", "CREATECONSUMER", key, group, "extra" + random.nextInt(3)};
            }
            Logged result = primaryRun(primary, clock, command);
            follower.apply(result.commands());
        }
    }

    @Test
    void aFollowerThatReplaysWhatIsLoggedEndsUpWithTheSameStream() {
        for (long seed = 1; seed <= 25; seed++) {
            try (Follower follower = new Follower()) {
                TestSupport.Stack local = TestSupport.newStack();
                try {
                    CommandHandler primary = TestSupport.allCommands(local.store(), ReplicationRole.PRIMARY);
                    randomWorkload(primary, follower, new Random(seed), 500, "s");
                    assertEquals(dump(primary, "s"), dump(follower.handler, "s"), "seed " + seed);
                } finally {
                    local.stop();
                }
            }
        }
    }

    @Test
    void aStreamRebuiltFromASnapshotIsTheSameStreamIncludingEntriesPendingForDeletedMessages() {
        for (long seed = 100; seed < 120; seed++) {
            TestSupport.Stack local = TestSupport.newStack();
            try (Follower ignored = new Follower(); Follower rebuilt = new Follower()) {
                CommandHandler primary = TestSupport.allCommands(local.store(), ReplicationRole.PRIMARY);
                randomWorkload(primary, ignored, new Random(seed), 400, "s");
                primaryRun(primary, 1L, "XADD", "other", "1-1", "a", "b");
                String before = dump(primary, "s");
                assertTrue(before.contains("pel-count"), "there are groups to compare");

                List<SnapshotEntry> entries = new ArrayList<>();
                local.store().forEachSnapshot(entries::add);
                assertEquals(2, entries.size());
                for (SnapshotEntry entry : entries) {
                    for (List<String> command : entry.commands()) {
                        rebuilt.apply(List.of(command));
                    }
                }
                assertEquals(before, dump(rebuilt.handler, "s"), "seed " + seed);
                assertEquals(dump(primary, "other"), dump(rebuilt.handler, "other"));
            } finally {
                local.stop();
            }
        }
    }

    @Test
    void theSnapshotKeepsTheExpiryAndWorksAfterTheStreamWasEmptied() {
        run("XADD", "e", "1-1", "a", "1");
        run("XGROUP", "CREATE", "e", "g", "0");
        run("XDEL", "e", "1-1");
        run("PEXPIREAT", "e", "99999999999999");
        List<SnapshotEntry> entries = new ArrayList<>();
        stack.store().forEachSnapshot(entries::add);
        List<List<String>> commands = new ArrayList<>();
        entries.getFirst().commands().forEach(commands::add);
        assertEquals(List.of("PEXPIREAT", "e", "99999999999999"), commands.getLast());
        try (Follower follower = new Follower()) {
            follower.apply(commands);
            assertEquals(dump(handler, "e"), dump(follower.handler, "e"));
            assertEquals(99999999999999L, follower.stack.store().expiresAt("e"));
        }
    }

    // ------------------------------------------------------------------------------ small things

    @Test
    void xrestoreIsForSnapshotsOnly() {
        assertTrue(handler.find("XRESTORE").isAdmin(), "clients cannot call it");
        assertTrue(handler.find("XRESTORE").isWrite());
        run("XADD", "q", "1-1", "a", "b");
        run("XGROUP", "CREATE", "q", "g", "0");
        assertEquals("+OK", show("XRESTORE", "q", "g", "c", "9-9", "123", "4"), "even for a message that is not there");
        assertTrue(show("XPENDING", "q", "g", "-", "+", "10").startsWith("[[\"9-9\", \"c\", :"));
        assertTrue(show("XRESTORE", "q", "nogroup", "c", "9-9", "1", "1").startsWith("-NOGROUP"));
        assertTrue(show("XRESTORE", "q", "g", "c", "bad", "1", "1").startsWith("-Invalid stream ID"));
        assertTrue(show("XRESTORE", "q", "g", "c", "9-9", "-1", "1").startsWith("-invalid XRESTORE"));
    }

    @Test
    void streamsHaveTheirOwnTypeAndAnEmptyStreamIsStillAKey() {
        run("XADD", "s", "1-1", "a", "1");
        assertEquals("+stream", show("TYPE", "s"));
        run("XDEL", "s", "1-1");
        assertEquals("+stream", show("TYPE", "s"), "unlike a list, an empty stream is not deleted");
        assertEquals(":0", show("XLEN", "s"));
        assertEquals(":1", show("EXISTS", "s"));
        run("XTRIM", "s", "MAXLEN", "0");
        assertEquals("[\"length\", :0, \"radix-tree-keys\", :0, \"radix-tree-nodes\", :1, \"last-generated-id\", \"1-1\", "
                + "\"max-deleted-entry-id\", \"1-1\", \"entries-added\", :1, \"recorded-first-entry-id\", \"0-0\", "
                + "\"groups\", :0, \"first-entry\", (nil), \"last-entry\", (nil)]", show("XINFO", "STREAM", "s"));
        assertTrue(show("GET", "s").startsWith("-WRONGTYPE"));
        assertTrue(show("LPUSH", "s", "x").startsWith("-WRONGTYPE"));
        run("SET", "str", "v");
        assertTrue(show("XADD", "str", "1-1", "a", "b").startsWith("-WRONGTYPE"));
    }

    @Test
    void renameMovesAStreamTogetherWithItsGroups() {
        run("XADD", "a", "1-1", "x", "y");
        run("XGROUP", "CREATE", "a", "g", "0");
        run("XREADGROUP", "GROUP", "g", "c", "STREAMS", "a", ">");
        assertEquals("+OK", show("RENAME", "a", "b"));
        assertEquals(":0", show("EXISTS", "a"));
        assertTrue(show("XPENDING", "b", "g").startsWith("[:1, \"1-1\", \"1-1\""));
    }

    @Test
    void theMemoryEstimateGrowsWithEntriesAndFallsWithTrimming() {
        long empty = stack.store().usedMemory();
        for (int i = 1; i <= 1000; i++) {
            run("XADD", "m", i + "-0", "payload", "x".repeat(100));
        }
        long full = stack.store().usedMemory();
        assertTrue(full > empty + 1000 * 100, "at least the payloads: " + (full - empty));
        run("XTRIM", "m", "MAXLEN", "10");
        assertTrue(stack.store().usedMemory() < empty + 10_000, "trimming gives the memory back: " + stack.store().usedMemory());
        run("DEL", "m");
        assertEquals(empty, stack.store().usedMemory());
    }

    @Test
    void blockingReadsResolveDollarToTheLastIdAndOtherCommandsDoNotBlock() {
        run("XADD", "s", "5-1", "a", "1");
        BlockingRequest request = handler.find("XREAD").blocking(List.of("XREAD", "BLOCK", "250", "STREAMS", "s", "nokey", "$", "$"));
        assertNotNull(request);
        assertEquals(250, request.timeoutMillis());
        assertEquals(List.of("s", "nokey"), request.keys());
        assertEquals(List.of("XREAD", "BLOCK", "250", "STREAMS", "s", "nokey", "5-1", "0-0"), request.command(),
                "$ is fixed to the last id when the command arrived, so an entry that comes meanwhile is delivered");
        assertFalse(request.worthTrying().getAsBoolean());
        run("XADD", "s", "6-0", "a", "2");
        assertTrue(request.worthTrying().getAsBoolean());

        assertNull(handler.find("XREAD").blocking(List.of("XREAD", "STREAMS", "s", "$")), "no BLOCK, no waiting");
        assertNull(handler.find("XREAD").blocking(List.of("XREAD", "BLOCK", "x", "STREAMS", "s", "$")), "an error is the command's to report");
        assertNull(handler.find("GET").blocking(List.of("GET", "s")));

        run("XGROUP", "CREATE", "s", "g", "$");
        BlockingRequest group = handler.find("XREADGROUP").blocking(
                List.of("XREADGROUP", "GROUP", "g", "c", "BLOCK", "0", "STREAMS", "s", ">"));
        assertNotNull(group);
        assertEquals(0, group.timeoutMillis(), "BLOCK 0 waits for ever");
        assertFalse(group.worthTrying().getAsBoolean(), "the group has seen everything");
        run("XADD", "s", "7-0", "a", "3");
        assertTrue(group.worthTrying().getAsBoolean());
        assertNull(handler.find("XREADGROUP").blocking(List.of("XREADGROUP", "GROUP", "g", "c", "BLOCK", "0", "STREAMS", "s", "0")),
                "reading one's own history never waits");
    }

    @Test
    void aBlockingReadThatRunsDirectlyNeverWaits() {
        run("XADD", "s", "1-1", "a", "1");
        assertEquals("(nilarray)", show("XREAD", "BLOCK", "60000", "STREAMS", "s", "$"),
                "as inside MULTI: BLOCK is accepted and ignored");
        run("XGROUP", "CREATE", "s", "g", "$");
        assertEquals("(nilarray)", show("XREADGROUP", "GROUP", "g", "c", "BLOCK", "0", "STREAMS", "s", ">"));
    }
}
