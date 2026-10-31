package me.niteshh.redcake.command;

import me.niteshh.redcake.replication.ReplicationRole;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.store.ValueType;
import me.niteshh.redcake.support.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** The vector-set commands (VADD, VSIM, ...) as a client sees them, and their round trip through a snapshot. */
class VectorCommandsTest {

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

    private RespValue run(List<String> command) {
        return handler.handle(command);
    }

    private static List<String> strings(RespValue value) {
        List<String> out = new ArrayList<>();
        for (RespValue element : assertInstanceOf(ArrayValue.class, value).elements()) {
            out.add(element instanceof BulkString b ? b.value() : "(nil)");
        }
        return out;
    }

    private static void assertError(RespValue value, String contains) {
        ErrorValue error = assertInstanceOf(ErrorValue.class, value);
        assertTrue(error.message().contains(contains), error.message());
    }

    private static String blob(float... values) {
        ByteBuffer buffer = ByteBuffer.allocate(4 * values.length).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : values) {
            buffer.putFloat(v);
        }
        return new String(buffer.array(), StandardCharsets.ISO_8859_1);
    }

    private void addValues(String key, String element, String... components) {
        List<String> command = new ArrayList<>(List.of("VADD", key, "VALUES", Integer.toString(components.length)));
        command.addAll(List.of(components));
        command.add(element);
        assertEquals(new IntegerValue(1), run(command));
    }

    private void seed() {
        addValues("v", "east", "1", "0", "0");
        addValues("v", "north", "0", "1", "0");
        addValues("v", "up", "0", "0", "1");
        addValues("v", "northeast", "1", "1", "0");
    }

    // ------------------------------------------------------------------ adding and describing

    @Test
    void addingReportsNewVersusUpdatedAndTheSetIsDescribed() {
        assertEquals(new IntegerValue(1), run("VADD", "v", "VALUES", "3", "1", "2", "3", "a"));
        assertEquals(new IntegerValue(0), run("VADD", "v", "VALUES", "3", "3", "2", "1", "a"), "second time it is an update");
        assertEquals(new IntegerValue(1), run("VADD", "v", "FP32", blob(0, 0, 1), "b"));
        assertEquals(new IntegerValue(2), run("VCARD", "v"));
        assertEquals(new IntegerValue(3), run("VDIM", "v"));
        assertEquals("vectorset", stack.store().type("v").redisName());
        assertEquals(new IntegerValue(1), run("VISMEMBER", "v", "a"));
        assertEquals(new IntegerValue(0), run("VISMEMBER", "v", "nope"));
        assertEquals(new IntegerValue(0), run("VISMEMBER", "missing", "a"));
        assertEquals(new IntegerValue(0), run("VCARD", "missing"));
        assertError(run("VDIM", "missing"), "key does not exist");
    }

    @Test
    void valuesAreRewrittenToAnFp32BlobBeforeTheyAreLoggedAndReplicated() {
        List<String> normalized = handler.normalize(
                List.of("VADD", "v", "VALUES", "3", "1", "0.5", "-2", "elem", "Q8", "SETATTR", "{}"), 0);
        assertEquals(List.of("VADD", "v", "FP32", blob(1, 0.5f, -2), "elem", "Q8", "SETATTR", "{}"), normalized);
        // anything it cannot parse is left alone for execute() to report
        List<String> broken = List.of("VADD", "v", "VALUES", "3", "1", "x", "2", "elem");
        assertEquals(broken, handler.normalize(broken, 0));
        List<String> blobbed = List.of("VADD", "v", "FP32", blob(1, 2), "elem");
        assertEquals(blobbed, handler.normalize(blobbed, 0));
    }

    @Test
    void optionsChooseTheStorageAndAreCheckedAgainstAnExistingSet() {
        assertEquals(new IntegerValue(1), run("VADD", "q", "VALUES", "2", "1", "2", "a", "NOQUANT", "M", "8"));
        assertEquals("fp32", strings(run("VINFO", "q")).get(1));
        assertEquals(new IntegerValue(1), run("VADD", "q", "VALUES", "2", "2", "1", "b", "NOQUANT", "M", "8"));
        assertError(run("VADD", "q", "VALUES", "2", "2", "1", "c", "Q8"), "quantization mismatch");
        assertError(run("VADD", "q", "VALUES", "2", "2", "1", "c", "M", "12"), "M value mismatch");
        assertEquals(new IntegerValue(2), run("VCARD", "q"), "the failed adds changed nothing");
        assertEquals(new IntegerValue(1), run("VADD", "bin", "VALUES", "2", "1", "-1", "a", "BIN"));
        assertEquals("bin", strings(run("VINFO", "bin")).get(1));
        assertEquals(new IntegerValue(1), run("VADD", "def", "VALUES", "2", "1", "1", "a"));
        assertEquals("int8", strings(run("VINFO", "def")).get(1), "int8 is the default");
        assertEquals(new IntegerValue(1), run("VADD", "ef", "VALUES", "2", "1", "1", "a", "CAS", "EF", "50"));
    }

    @Test
    void badArgumentsGetClearErrorsAndChangeNothing() {
        assertError(run("VADD", "v", "VALUES", "3", "1", "2", "a"), "invalid vector component 'a'");
        assertError(run("VADD", "v", "VALUES", "3", "1", "2", "3"), "syntax error"); // no element name
        assertError(run("VADD", "v", "VALUES", "2", "1", "x", "a"), "invalid vector component");
        assertError(run("VADD", "v", "VALUES", "2", "1", "0x10", "a"), "invalid vector component");
        assertError(run("VADD", "v", "VALUES", "2", "1", "2f", "a"), "invalid vector component");
        assertError(run("VADD", "v", "VALUES", "2", "1", "NaN", "a"), "invalid vector component");
        assertError(run("VADD", "v", "VALUES", "2", "1", "1e999", "a"), "NaN or infinity");
        assertError(run("VADD", "v", "VALUES", "2", "0", "0", "a"), "all components are zero");
        assertError(run("VADD", "v", "VALUES", "0", "a"), "invalid vector dimension");
        assertError(run("VADD", "v", "FP32", "abc", "a"), "invalid FP32 vector");
        assertError(run("VADD", "v", "FP32", "", "a"), "invalid FP32 vector");
        assertError(run("VADD", "v", "NOPE", "x", "a"), "syntax error");
        assertError(run("VADD", "v", "VALUES", "1", "1", "a", "WHATEVER"), "syntax error");
        assertError(run("VADD", "v", "VALUES", "1", "1", "a", "EF", "0"), "EF must be between");
        assertError(run("VADD", "v", "VALUES", "1", "1", "a", "M", "1"), "M must be between");
        assertError(run("VADD", "v", "VALUES", "1", "1", "a", "Q8", "BIN"), "syntax error");
        assertError(run("VADD", "v", "REDUCE", "2", "VALUES", "3", "1", "2", "3", "a"), "REDUCE is not supported");
        assertError(run("VADD", "v", "VALUES"), "wrong number of arguments");
        assertNull(stack.store().type("v"), "no failed command created the key");

        addValues("v", "a", "1", "2", "3");
        assertError(run("VADD", "v", "VALUES", "2", "1", "2", "b"), "dimension mismatch - got 2 but set has 3");
        assertEquals(new IntegerValue(1), run("VCARD", "v"));
    }

    // ------------------------------------------------------------------ searching

    @Test
    void similaritySearchRanksByCosineAndScoresRunFromZeroToOne() {
        seed();
        assertEquals(List.of("east", "northeast", "north", "up"), strings(run("VSIM", "v", "VALUES", "3", "1", "0.1", "0")));
        assertEquals(List.of("east", "northeast"), strings(run("VSIM", "v", "ELE", "east", "COUNT", "2")));

        List<String> scored = strings(run("VSIM", "v", "ELE", "east", "WITHSCORES", "COUNT", "4"));
        assertEquals(List.of("east", "northeast"), List.of(scored.get(0), scored.get(2)));
        // north and up are exactly as far from east: their relative order is a tie, not something to rely on
        assertEquals(java.util.Set.of("north", "up"), java.util.Set.of(scored.get(4), scored.get(6)));
        assertEquals("1", scored.get(1), "identical = 1");
        assertEquals(0.5 + Math.sqrt(0.5) / 2, Double.parseDouble(scored.get(3)), 1e-2); // cos 45 degrees
        assertEquals(0.5, Double.parseDouble(scored.get(5)), 1e-2, "orthogonal = 0.5");

        assertEquals(strings(run("VSIM", "v", "FP32", blob(1, 0, 0))), strings(run("VSIM", "v", "ELE", "east")));
    }

    @Test
    void epsilonKeepsOnlyCloseResultsAndTruthForcesAnExactScan() {
        seed();
        // score >= 1 - epsilon: east is 1.0, northeast about 0.85
        assertEquals(List.of("east"), strings(run("VSIM", "v", "ELE", "east", "EPSILON", "0.05")));
        assertEquals(List.of("east", "northeast"), strings(run("VSIM", "v", "ELE", "east", "EPSILON", "0.2")));
        assertEquals(strings(run("VSIM", "v", "ELE", "east")), strings(run("VSIM", "v", "ELE", "east", "TRUTH")));
        assertEquals(strings(run("VSIM", "v", "ELE", "east", "EF", "200", "NOTHREAD")),
                strings(run("VSIM", "v", "ELE", "east")));
    }

    @Test
    void attributesAreStoredReturnedAndKeptAcrossUpdates() {
        assertEquals(new IntegerValue(1), run("VADD", "v", "VALUES", "2", "1", "0", "a", "SETATTR", "{\"year\":1999}"));
        assertEquals(new IntegerValue(1), run("VADD", "v", "VALUES", "2", "0", "1", "b"));
        assertEquals(new BulkString("{\"year\":1999}"), run("VGETATTR", "v", "a"));
        assertEquals(new NullValue(), run("VGETATTR", "v", "b"));
        assertEquals(new NullValue(), run("VGETATTR", "v", "nope"));
        assertEquals(new NullValue(), run("VGETATTR", "missing", "a"));

        assertEquals(new IntegerValue(1), run("VSETATTR", "v", "b", "{\"year\":2024}"));
        assertEquals(new IntegerValue(0), run("VSETATTR", "v", "nope", "x"));
        assertEquals(new IntegerValue(0), run("VSETATTR", "missing", "a", "x"));
        assertEquals(List.of("a", "{\"year\":1999}", "b", "{\"year\":2024}"),
                strings(run("VSIM", "v", "ELE", "a", "WITHATTRIBS")).stream().limit(4).toList());

        assertEquals(new IntegerValue(0), run("VADD", "v", "VALUES", "2", "1", "1", "a")); // update without SETATTR
        assertEquals(new BulkString("{\"year\":1999}"), run("VGETATTR", "v", "a"), "attribute survives a vector update");
        assertEquals(new IntegerValue(1), run("VSETATTR", "v", "a", ""));
        assertEquals(new NullValue(), run("VGETATTR", "v", "a"), "empty string clears it");

        List<String> both = strings(run("VSIM", "v", "ELE", "b", "WITHSCORES", "WITHATTRIBS", "COUNT", "1"));
        assertEquals(List.of("b", "1", "{\"year\":2024}"), both, "element, score, attributes");
    }

    @Test
    void searchErrorsAndEmptyResults() {
        assertEquals(ArrayValue.empty(), run("VSIM", "missing", "ELE", "x"));
        seed();
        assertError(run("VSIM", "v", "ELE", "nope"), "element not found");
        assertError(run("VSIM", "v", "VALUES", "2", "1", "0"), "dimension mismatch");
        assertError(run("VSIM", "v", "VALUES", "3", "0", "0", "0"), "all components are zero");
        assertError(run("VSIM", "v", "ELE", "east", "COUNT", "0"), "COUNT must be between");
        assertError(run("VSIM", "v", "ELE", "east", "COUNT", "100000"), "COUNT must be between");
        assertError(run("VSIM", "v", "ELE", "east", "EF", "0"), "EF must be between");
        assertError(run("VSIM", "v", "ELE", "east", "EPSILON", "-1"), "EPSILON");
        assertError(run("VSIM", "v", "ELE", "east", "FILTER", ".a > 1"), "FILTER is not supported");
        assertError(run("VSIM", "v", "ELE", "east", "BOGUS"), "syntax error");
        assertError(run("VSIM", "v", "WHAT", "east"), "syntax error");
        assertError(run("VSIM", "v", "ELE", "east", "COUNT"), "syntax error");
    }

    // ------------------------------------------------------------------ the rest of the family

    @Test
    void removingElementsAndThatTheKeyGoesWithTheLastOne() {
        seed();
        assertEquals(new IntegerValue(1), run("VREM", "v", "east"));
        assertEquals(new IntegerValue(0), run("VREM", "v", "east"));
        assertEquals(new IntegerValue(0), run("VREM", "missing", "east"));
        assertEquals(new IntegerValue(3), run("VCARD", "v"));
        assertFalse(strings(run("VSIM", "v", "VALUES", "3", "1", "0", "0")).contains("east"));
        run("VREM", "v", "north");
        run("VREM", "v", "up");
        assertEquals(new IntegerValue(1), run("VREM", "v", "northeast"));
        assertNull(stack.store().type("v"), "an empty vector set is deleted, like any empty collection");
        assertEquals(new IntegerValue(0), run("EXISTS", "v"));
    }

    @Test
    void storedVectorsComeBackNormalizedOrRaw() {
        assertEquals(new IntegerValue(1), run("VADD", "v", "VALUES", "3", "3", "0", "4", "a", "NOQUANT"));
        List<String> values = strings(run("VEMB", "v", "a"));
        assertEquals(List.of("0.6", "0", "0.8"), values);
        List<String> raw = strings(run("VEMB", "v", "a", "RAW"));
        assertEquals("fp32", raw.get(0));
        assertEquals(blob(0.6f, 0f, 0.8f), raw.get(1));
        assertEquals("5", raw.get(2), "the length before normalization");
        assertEquals(new NullValue(), run("VEMB", "v", "nope"));
        assertEquals(new NullValue(), run("VEMB", "missing", "a"));
        assertError(run("VEMB", "v", "a", "BOGUS"), "syntax error");

        run("VADD", "q", "VALUES", "3", "3", "0", "4", "a");
        List<String> approx = strings(run("VEMB", "q", "a"));
        assertEquals(0.6, Double.parseDouble(approx.get(0)), 0.01);
        assertEquals(0.8, Double.parseDouble(approx.get(2)), 0.01);
        assertEquals("int8", strings(run("VEMB", "q", "a", "RAW")).get(0));
    }

    @Test
    void infoLinksAndRandomMembers() {
        seed();
        List<String> info = strings(run("VINFO", "v"));
        assertEquals("quant-type", info.get(0));
        assertEquals("int8", info.get(1));
        assertEquals("vector-dim", info.get(2));
        List<RespValue> fields = ((ArrayValue) run("VINFO", "v")).elements();
        assertEquals(new IntegerValue(4), fields.get(fields.indexOf(new BulkString("size")) + 1));
        assertEquals(new NullValue(), run("VINFO", "missing"));

        ArrayValue levels = assertInstanceOf(ArrayValue.class, run("VLINKS", "v", "east"));
        assertFalse(levels.elements().isEmpty());
        List<String> level0 = strings(levels.elements().getFirst());
        assertEquals(3, level0.size(), "the other three elements are its neighbours in a tiny graph");
        assertEquals(6, strings(((ArrayValue) run("VLINKS", "v", "east", "WITHSCORES")).elements().getFirst()).size());
        assertEquals(new NullValue(), run("VLINKS", "v", "nope"));
        assertEquals(new NullValue(), run("VLINKS", "missing", "east"));

        assertInstanceOf(BulkString.class, run("VRANDMEMBER", "v"));
        assertEquals(new NullValue(), run("VRANDMEMBER", "missing"));
        assertEquals(2, strings(run("VRANDMEMBER", "v", "2")).size());
        assertEquals(4, strings(run("VRANDMEMBER", "v", "100")).size(), "positive count: distinct, at most the set");
        assertEquals(7, strings(run("VRANDMEMBER", "v", "-7")).size(), "negative count: exactly that many, may repeat");
        assertEquals(ArrayValue.empty(), run("VRANDMEMBER", "missing", "3"));
    }

    // ------------------------------------------------------------------ interplay with other types and commands

    @Test
    void vectorSetsAndOtherTypesRefuseEachOther() {
        run("SET", "str", "x");
        assertError(run("VADD", "str", "VALUES", "1", "1", "a"), "WRONGTYPE");
        assertError(run("VSIM", "str", "ELE", "a"), "WRONGTYPE");
        assertError(run("VCARD", "str"), "WRONGTYPE");
        assertError(run("VREM", "str", "a"), "WRONGTYPE");

        seed();
        assertError(run("GET", "v"), "WRONGTYPE");
        assertError(run("HSET", "v", "f", "1"), "WRONGTYPE");
        assertError(run("APPEND", "v", "x"), "WRONGTYPE");
        assertError(run("INCR", "v"), "WRONGTYPE");
        assertThrows(me.niteshh.redcake.store.WrongTypeException.class, () -> stack.store().peek("str", ValueType.VECTORSET));
        assertNull(stack.store().peek("absent", ValueType.VECTORSET));
        assertEquals(new SimpleString("OK"), run("SET", "v", "overwritten"), "SET replaces any type, as in Redis");
    }

    @Test
    void renameExpiryDeleteAndMemoryAccountingWorkLikeForAnyKey() {
        seed();
        long before = stack.store().usedMemory();
        assertTrue(before > 0);
        assertEquals(new SimpleString("OK"), run("RENAME", "v", "w"));
        assertEquals(new IntegerValue(4), run("VCARD", "w"));
        assertEquals(new IntegerValue(0), run("EXISTS", "v"));
        assertEquals(new IntegerValue(1), run("EXPIRE", "w", "1000"));
        assertTrue(stack.store().pttl("w") > 0);

        for (int i = 0; i < 300; i++) {
            run("VADD", "w", "VALUES", "3", Integer.toString(i), "1", "2", "extra:" + i);
        }
        assertTrue(stack.store().usedMemory() > before + 300 * 10, "memory grows with the elements");
        assertEquals(new IntegerValue(1), run("DEL", "w"));
        assertEquals(0, stack.store().usedMemory(), "and is released with the key");
    }

    // ------------------------------------------------------------------ work done before the write lock

    /** What the server does for a write: prepare outside the lock, then normalize and execute with the result handed over. */
    private RespValue runLikeTheServer(String... command) {
        RedCakeCommand known = handler.find(command[0]);
        Object prepared = known.prepare(List.of(command));
        List<String> normalized = handler.normalize(List.of(command), 0);
        PreparedWork.set(prepared);
        try {
            return handler.handle(normalized);
        } finally {
            PreparedWork.clear();
        }
    }

    @Test
    void preparingBeforeTheLockGivesTheSameResultAsExecutingDirectly() {
        Random random = new Random(8);
        for (int i = 0; i < 300; i++) {
            List<String> components = randomVector(random, 12);
            List<String> command = new ArrayList<>(List.of("VADD", "p", "VALUES", "12"));
            command.addAll(components);
            command.add("e" + i);
            assertEquals(new IntegerValue(1), runLikeTheServer(command.toArray(String[]::new)), "element " + i);
        }
        assertEquals(new IntegerValue(300), run("VCARD", "p"));
        // updates are not planned ahead, but must work the same way
        assertEquals(new IntegerValue(0), runLikeTheServer("VADD", "p", "VALUES", "12",
                "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "e5"));
        assertEquals("e5", strings(run("VSIM", "p", "VALUES", "12",
                "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "COUNT", "1")).getFirst());
    }

    @Test
    void preparedWorkThatDoesNotBelongToTheCommandIsIgnored() {
        addValues("v", "a", "1", "0", "0");
        addValues("v", "b", "0", "1", "0");
        RedCakeCommand vadd = handler.find("VADD");

        // prepared for element "x", then the command that actually runs adds "y" with another vector
        Object forX = vadd.prepare(List.of("VADD", "v", "VALUES", "3", "5", "5", "5", "x"));
        assertNotNull(forX);
        PreparedWork.set(forX);
        try {
            assertEquals(new IntegerValue(1), handler.handle(List.of("VADD", "v", "VALUES", "3", "0", "0", "1", "y")));
        } finally {
            PreparedWork.clear();
        }
        assertEquals(new IntegerValue(3), run("VCARD", "v"));
        assertEquals(new IntegerValue(0), run("VISMEMBER", "v", "x"));
        assertEquals("y", strings(run("VSIM", "v", "VALUES", "3", "0", "0", "1", "COUNT", "1")).getFirst());

        // prepared work of another type is not mistaken for a plan
        PreparedWork.set("not a plan");
        try {
            assertEquals(new IntegerValue(1), handler.handle(List.of("VADD", "v", "VALUES", "3", "1", "1", "1", "z")));
        } finally {
            PreparedWork.clear();
        }

        // prepared against a set that has been replaced since
        Object forOld = vadd.prepare(List.of("VADD", "v", "VALUES", "3", "2", "2", "2", "w"));
        run("DEL", "v");
        addValues("v", "fresh", "1", "0", "0");
        PreparedWork.set(forOld);
        try {
            assertEquals(new IntegerValue(1), handler.handle(List.of("VADD", "v", "VALUES", "3", "2", "2", "2", "w")));
        } finally {
            PreparedWork.clear();
        }
        assertEquals(new IntegerValue(2), run("VCARD", "v"));
    }

    @Test
    void prepareNeverThrowsAndHasNothingToDoForBadOrNewKeys() {
        RedCakeCommand vadd = handler.find("VADD");
        assertNull(vadd.prepare(List.of("VADD", "new-key", "VALUES", "2", "1", "2", "a")), "no set yet: nothing to search");
        run("SET", "text", "x");
        assertNull(vadd.prepare(List.of("VADD", "text", "VALUES", "2", "1", "2", "a")), "WRONGTYPE is execute's to report");
        addValues("v", "a", "1", "2", "3");
        assertNull(vadd.prepare(List.of("VADD", "v", "VALUES", "2", "1", "2", "b")), "wrong dimension");
        assertNull(vadd.prepare(List.of("VADD", "v", "VALUES", "3", "1", "x", "3", "b")), "garbage");
        assertNull(vadd.prepare(List.of("VADD", "v")), "too short");
        assertNull(vadd.prepare(List.of("VADD")), "nothing at all");
        assertNull(vadd.prepare(List.of("VADD", "v", "FP32", "abc", "b")), "bad blob");
        assertNotNull(vadd.prepare(List.of("VADD", "v", "VALUES", "3", "3", "2", "1", "b")));
        assertNull(handler.find("VREM").prepare(List.of("VREM", "v", "a")), "other commands prepare nothing");
        assertNull(handler.find("SET").prepare(List.of("SET", "k", "v")));
    }

    // ------------------------------------------------------------------ snapshots (AOF rewrite, replica full sync)

    private List<List<String>> snapshotCommands(String key) {
        List<List<String>> out = new ArrayList<>();
        stack.store().forEachSnapshot(entry -> {
            if (entry.key().equals(key)) {
                for (List<String> command : entry.commands()) {
                    out.add(command);
                }
            }
        });
        return out;
    }

    private static List<String> randomVector(Random random, int dim) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < dim; i++) {
            out.add(Float.toString((float) random.nextGaussian()));
        }
        return out;
    }

    @Test
    void aSnapshotRebuildsAnIdenticalSetOnAnotherServerAndKeepsTheTtl() {
        Random random = new Random(5);
        for (int i = 0; i < 400; i++) {
            List<String> command = new ArrayList<>(List.of("VADD", "v", "VALUES", "16"));
            command.addAll(randomVector(random, 16));
            command.add("e" + i);
            if (i % 10 == 0) {
                command.addAll(List.of("SETATTR", "{\"i\":" + i + "}"));
            }
            run(handler.normalize(command, 0));
        }
        for (int i = 0; i < 400; i += 3) {
            run("VREM", "v", "e" + i);
        }
        long expiry = System.currentTimeMillis() + 3_600_000;
        run("PEXPIREAT", "v", Long.toString(expiry));

        List<List<String>> commands = snapshotCommands("v");
        assertEquals(List.of("VRESTORE", "v", "HEADER"), commands.getFirst().subList(0, 3));
        assertEquals(List.of("VRESTORE", "v", "END"), commands.get(commands.size() - 2));
        assertEquals(List.of("PEXPIREAT", "v", Long.toString(expiry)), commands.getLast());

        TestSupport.Stack other = TestSupport.newStack();
        try {
            CommandHandler replica = TestSupport.allCommands(other.store(), ReplicationRole.PRIMARY);
            for (List<String> command : commands) {
                RespValue reply = replica.handle(command);
                assertTrue(reply instanceof SimpleString || reply instanceof IntegerValue,
                        command.subList(0, 3) + " -> " + reply);
            }
            assertEquals(run("VCARD", "v"), replica.handle(List.of("VCARD", "v")));
            assertEquals(expiry, other.store().expiresAt("v"));
            assertEquals(run("VGETATTR", "v", "e10"), replica.handle(List.of("VGETATTR", "v", "e10")));
            List<String> query = new ArrayList<>(List.of("VSIM", "v", "ELE", "e1", "COUNT", "20", "WITHSCORES"));
            assertEquals(strings(run(query)), strings(replica.handle(query)));
            // and the copy is a normal, writable set
            assertEquals(new IntegerValue(0), replica.handle(List.of("VADD", "v", "VALUES", "16",
                    "1", "2", "3", "4", "5", "6", "7", "8", "1", "2", "3", "4", "5", "6", "7", "8", "e1")));
        } finally {
            other.stop();
        }
    }

    @Test
    void anEmptyAndAMissingSetProduceNoSnapshotAndRestoringReplacesWhatWasThere() {
        assertTrue(snapshotCommands("none").isEmpty());
        seed();
        List<List<String>> commands = snapshotCommands("v");
        run("SET", "other", "text");
        // restoring over a key of another type replaces it
        List<List<String>> asOther = new ArrayList<>();
        for (List<String> command : commands) {
            List<String> renamed = new ArrayList<>(command);
            renamed.set(1, "other");
            asOther.add(renamed);
        }
        asOther.forEach(this::run);
        assertEquals(new IntegerValue(4), run("VCARD", "other"));
    }

    @Test
    void vrestoreRefusesBrokenInputAndDeletesWhatItStarted() {
        seed();
        List<List<String>> commands = snapshotCommands("v");
        assertEquals(new SimpleString("OK"), run(commands.getFirst().stream().map(s -> s.equals("v") ? "r" : s).toList()));
        assertError(run("VRESTORE", "r", "NODES", "5", "xx"), "out of order");
        assertNull(stack.store().type("r"), "a failed restore leaves no half-loaded set behind");
        assertError(run("VRESTORE", "r", "NODES", "0", "xx"), "no vector set restore in progress");
        assertError(run("VRESTORE", "r", "END"), "no vector set restore in progress");
        assertError(run("VRESTORE", "r", "HEADER", "garbage"), "bad vector dump header");
        assertError(run("VRESTORE", "r", "WHAT"), "syntax error");
        assertError(run("VRESTORE", "r"), "wrong number of arguments");

        // header ok, but END before the nodes arrive
        run(commands.getFirst().stream().map(s -> s.equals("v") ? "r" : s).toList());
        assertError(run("VRESTORE", "r", "END"), "incomplete");
        assertNull(stack.store().type("r"));
    }

    @Test
    void theRestoreCommandNeedsTheAdminRole() {
        assertTrue(handler.find("VRESTORE").isAdmin());
        assertTrue(handler.find("VRESTORE").isWrite());
        assertTrue(handler.find("VADD").isWrite());
        assertTrue(handler.find("VADD").growsMemory());
        assertTrue(handler.find("VREM").isWrite());
        assertFalse(handler.find("VREM").growsMemory(), "removing is allowed when memory is full");
        assertFalse(handler.find("VSIM").isWrite());
        assertFalse(handler.find("VEMB").isWrite());
        assertTrue(handler.find("VSETATTR").isWrite());
    }
}
