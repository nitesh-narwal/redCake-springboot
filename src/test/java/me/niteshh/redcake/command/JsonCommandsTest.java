package me.niteshh.redcake.command;

import me.niteshh.redcake.replication.ReplicationRole;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.store.SnapshotEntry;
import me.niteshh.redcake.support.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The JSON.* commands as a client sees them: replies in both path dialects, errors, and how a document is kept. */
class JsonCommandsTest {

    private static final String DOC = "{\"a\":2,\"b\":3,\"nested\":{\"a\":4,\"b\":null}}";

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

    private static RespValue bulk(String text) {
        return new BulkString(text);
    }

    private static RespValue integer(long value) {
        return new IntegerValue(value);
    }

    private static RespValue array(RespValue... elements) {
        return new ArrayValue(List.of(elements));
    }

    private static RespValue nil() {
        return new NullValue();
    }

    private static final RespValue OK = new SimpleString("OK");

    private static void assertError(RespValue value, String contains) {
        ErrorValue error = assertInstanceOf(ErrorValue.class, value);
        assertTrue(error.message().contains(contains), error.message());
    }

    private String get(String key, String... paths) {
        List<String> command = new ArrayList<>(List.of("JSON.GET", key));
        command.addAll(List.of(paths));
        return ((BulkString) handler.handle(command)).value();
    }

    private void seed() {
        assertEquals(OK, run("JSON.SET", "doc", "$", DOC));
    }

    // ----------------------------------------------------------------------------------------------- set and get

    @Test
    void aDocumentIsStoredAndReadBackByPath() {
        seed();
        assertEquals(bulk(DOC), run("JSON.GET", "doc"), "no path: the whole document, unwrapped, like a legacy path");
        assertEquals(bulk("[" + DOC + "]"), run("JSON.GET", "doc", "$"), "a JSONPath answers with an array");
        assertEquals(bulk("[3,null]"), run("JSON.GET", "doc", "$..b"));
        assertEquals(bulk("[2]"), run("JSON.GET", "doc", "$.a"));
        assertEquals(bulk("2"), run("JSON.GET", "doc", ".a"));
        assertEquals(bulk("{\"a\":4,\"b\":null}"), run("JSON.GET", "doc", "nested"));
        assertEquals(bulk("[]"), run("JSON.GET", "doc", "$.nothing"));
        assertEquals(bulk("{\"$.a\":[2],\"$..b\":[3,null]}"), run("JSON.GET", "doc", "$.a", "$..b"), "several paths: an object keyed by path");
        assertEquals(bulk("{\".a\":2,\"$.b\":[3]}"), run("JSON.GET", "doc", ".a", "$.b"));
        assertError(run("JSON.GET", "doc", ".nothing"), "Path '.nothing' does not exist");
        assertError(run("JSON.GET", "doc", ".a", "$.a", ".nothing"), "does not exist");
        assertEquals(nil(), run("JSON.GET", "missing"));
        assertEquals(nil(), run("JSON.GET", "missing", "$.a"));
        assertEquals("ReJSON-RL", stack.store().type("doc").redisName());
        assertEquals(new SimpleString("ReJSON-RL"), run("TYPE", "doc"));
        assertEquals(OK, run("json.set", "lower", "$", "1"), "command names are case-insensitive");
    }

    @Test
    void formatsLikeRedisJson() {
        assertEquals(OK, run("JSON.SET", "f", "$", "{\"a\":[1,2],\"b\":{\"c\":true},\"e\":{}}"));
        assertEquals(bulk("{\n  \"a\": [\n    1,\n    2\n  ],\n  \"b\": {\n    \"c\": true\n  },\n  \"e\": {}\n}"),
                run("JSON.GET", "f", "INDENT", "  ", "NEWLINE", "\n", "SPACE", " "));
        assertEquals(bulk("{\"a\": [1,2],\"b\": {\"c\": true},\"e\": {}}"), run("JSON.GET", "f", "SPACE", " "));
        assertEquals(bulk("[\n\t[\n\t\t1,\n\t\t2\n\t]\n]"), run("JSON.GET", "f", "$.a", "indent", "\t", "newline", "\n"), "options in any case, before or after the path");
        assertEquals(bulk("{\n\t\"$.a\": [\n\t\t[\n\t\t\t1,\n\t\t\t2\n\t\t]\n\t],\n\t\"$.e\": [\n\t\t{}\n\t]\n}"),
                run("JSON.GET", "f", "INDENT", "\t", "NEWLINE", "\n", "SPACE", " ", "$.a", "$.e"));
        assertError(run("JSON.GET", "f", "INDENT"), "syntax error");
    }

    @Test
    void setCreatesReplacesAndRefuses() {
        assertError(run("JSON.SET", "k", "$.a", "1"), "new objects must be created at the root");
        assertEquals(0, stack.store().exists("k") ? 1 : 0, "a refused SET creates nothing");
        assertError(run("JSON.SET", "k", "$", "{bad"), "invalid JSON");
        assertFalse(stack.store().exists("k"), "invalid JSON creates nothing");
        assertEquals(OK, run("JSON.SET", "k", "$", "{\"a\":1}"));
        assertEquals(OK, run("JSON.SET", "k", "$.b", "[1,2,3]"));
        assertEquals(OK, run("JSON.SET", "k", ".a", "{\"x\":null}"));
        assertEquals("{\"a\":{\"x\":null},\"b\":[1,2,3]}", get("k"));
        assertError(run("JSON.SET", "k", "$.c.d", "1"), "Path '$.c.d' does not exist");
        assertError(run("JSON.SET", "k", "$.b[7]", "1"), "does not exist");
        assertError(run("JSON.SET", "k", "$.a..", "1"), "invalid path");
        assertError(run("JSON.SET", "k", "$.a", "1", "MAYBE"), "syntax error");
        assertEquals("{\"a\":{\"x\":null},\"b\":[1,2,3]}", get("k"), "refused commands changed nothing");
        assertEquals(OK, run("JSON.SET", "k", "$", "[]"), "a document may be empty and is still a document");
        assertEquals(bulk("[]"), run("JSON.GET", "k"));
        assertTrue(stack.store().exists("k"));
        assertEquals(OK, run("JSON.SET", "scalar", "$", "\"just a string\""));
        assertEquals(bulk("[\"just a string\"]"), run("JSON.GET", "scalar", "$"));
    }

    @Test
    void nxAndXx() {
        assertEquals(nil(), run("JSON.SET", "k", "$", "1", "XX"), "XX on a missing key");
        assertFalse(stack.store().exists("k"));
        assertEquals(OK, run("JSON.SET", "k", "$", "{\"a\":1}", "NX"));
        assertEquals(nil(), run("JSON.SET", "k", "$", "{\"a\":2}", "NX"));
        assertEquals(nil(), run("JSON.SET", "k", "$.a", "5", "NX"));
        assertEquals(OK, run("JSON.SET", "k", "$.b", "5", "nx"));
        assertEquals(OK, run("JSON.SET", "k", "$.a", "9", "XX"));
        assertEquals(nil(), run("JSON.SET", "k", "$.zzz", "9", "XX"));
        assertEquals("{\"a\":9,\"b\":5}", get("k"));
        assertError(run("JSON.SET", "missing", "$.a", "1", "NX"), "new objects must be created at the root");
    }

    @Test
    void anotherTypeIsNeverOverwritten() {
        run("SET", "s", "plain");
        run("HSET", "h", "f", "v");
        for (String key : List.of("s", "h")) {
            assertError(run("JSON.SET", key, "$", "1"), "WRONGTYPE");
            assertError(run("JSON.GET", key), "WRONGTYPE");
            assertError(run("JSON.DEL", key), "WRONGTYPE");
            assertError(run("JSON.ARRAPPEND", key, "$", "1"), "WRONGTYPE");
        }
        assertEquals(bulk("plain"), run("GET", "s"));
        seed();
        assertEquals(array(bulk("[3]"), nil(), bulk("[3]")), run("JSON.MGET", "doc", "s", "doc", "$.b"),
                "MGET gives nil for what is not a JSON document");
        assertError(run("GET", "doc"), "WRONGTYPE");
    }

    @Test
    void expiryRenameAndDeleteTreatItAsAnyKey() {
        seed();
        assertEquals(integer(1), run("EXPIRE", "doc", "100"));
        assertEquals(OK, run("JSON.SET", "doc", "$.a", "10"));
        run("JSON.ARRAPPEND", "doc", "$.nothing", "1");
        assertTrue(stack.store().pttl("doc") > 90_000, "a change keeps the TTL");
        assertEquals(OK, run("JSON.SET", "doc", "$", "{\"replaced\":true}"));
        assertTrue(stack.store().pttl("doc") > 90_000, "replacing the whole document keeps it too");
        assertEquals(OK, run("RENAME", "doc", "moved"));
        assertEquals(bulk("{\"replaced\":true}"), run("JSON.GET", "moved"));
        assertTrue(stack.store().pttl("moved") > 90_000);
        assertEquals(integer(1), run("JSON.DEL", "moved", "$"), "deleting the root deletes the key");
        assertFalse(stack.store().exists("moved"));
        assertEquals(-2, stack.store().pttl("moved"));
        assertEquals(0, stack.store().expiringKeys());
        seed();
        assertEquals(integer(1), run("DEL", "doc"));
    }

    @Test
    void anEmptyDocumentIsNotDeletedLikeAnEmptyCollection() {
        assertEquals(OK, run("JSON.SET", "e", "$", "{}"));
        assertEquals(integer(1), run("EXISTS", "e"));
        assertEquals(OK, run("JSON.SET", "e", "$.x", "1"));
        assertEquals(integer(1), run("JSON.DEL", "e", "$.x"));
        assertEquals(integer(1), run("EXISTS", "e"), "removing the last member leaves {}");
        assertEquals(bulk("{}"), run("JSON.GET", "e"));
    }

    @Test
    void deleteAndForget() {
        seed();
        assertEquals(integer(2), run("JSON.DEL", "doc", "$..a"));
        assertEquals(integer(0), run("JSON.DEL", "doc", "$..a"));
        assertEquals(integer(1), run("JSON.FORGET", "doc", ".b"));
        assertEquals(integer(0), run("JSON.DEL", "doc", ".nothing"));
        assertEquals(integer(0), run("JSON.DEL", "missing"));
        assertEquals(bulk("{\"nested\":{\"b\":null}}"), run("JSON.GET", "doc"));
        assertEquals(integer(1), run("JSON.DEL", "doc"), "no path: the whole document");
        assertEquals(nil(), run("JSON.GET", "doc"));
    }

    @Test
    void typeNames() {
        assertEquals(OK, run("JSON.SET", "t", "$", "{\"o\":{},\"a\":[],\"s\":\"x\",\"i\":1,\"n\":1.5,\"b\":true,\"z\":null}"));
        assertEquals(array(bulk("object"), bulk("array"), bulk("string"), bulk("integer"), bulk("number"), bulk("boolean"), bulk("null")),
                run("JSON.TYPE", "t", "$.*"));
        assertEquals(bulk("object"), run("JSON.TYPE", "t"));
        assertEquals(array(bulk("object")), run("JSON.TYPE", "t", "$"));
        assertEquals(bulk("integer"), run("JSON.TYPE", "t", ".i"));
        assertEquals(array(), run("JSON.TYPE", "t", "$.nothing"));
        assertEquals(nil(), run("JSON.TYPE", "t", ".nothing"));
        assertEquals(nil(), run("JSON.TYPE", "missing"));
    }

    @Test
    void multiGet() {
        run("JSON.SET", "d1", "$", "{\"a\":1,\"l\":[1,2]}");
        run("JSON.SET", "d2", "$", "{\"a\":\"two\"}");
        run("JSON.SET", "d3", "$", "{\"b\":3}");
        assertEquals(array(bulk("[1]"), bulk("[\"two\"]"), bulk("[]"), nil()), run("JSON.MGET", "d1", "d2", "d3", "missing", "$.a"));
        assertEquals(array(bulk("1"), bulk("\"two\""), nil(), nil()), run("JSON.MGET", "d1", "d2", "d3", "missing", ".a"));
        assertEquals(array(bulk("[[1,2]]")), run("JSON.MGET", "d1", "$.l"));
    }

    // ---------------------------------------------------------------------------------------------- numbers

    @Test
    void numbersAreChangedInPlace() {
        seed();
        assertEquals(bulk("[4]"), run("JSON.NUMINCRBY", "doc", "$.a", "2"));
        assertEquals(bulk("[6,6]"), run("JSON.NUMINCRBY", "doc", "$..a", "2"));
        assertEquals(bulk("[3.5]"), run("JSON.NUMINCRBY", "doc", "$.b", "0.5"));
        assertEquals(bulk("[null]"), run("JSON.NUMINCRBY", "doc", "$.nested.b", "1"), "null is not a number");
        assertEquals(bulk("[]"), run("JSON.NUMINCRBY", "doc", "$.nothing", "1"));
        assertEquals(bulk("8"), run("JSON.NUMINCRBY", "doc", ".a", "2"), "a legacy path answers with the number");
        assertEquals(bulk("[24]"), run("JSON.NUMMULTBY", "doc", "$.a", "3"));
        assertEquals(bulk("48"), run("JSON.NUMMULTBY", "doc", ".a", "2"));
        assertEquals(bulk("[24.0]"), run("JSON.NUMMULTBY", "doc", "$.a", "0.5"));
        assertError(run("JSON.NUMINCRBY", "doc", ".nested.b", "1"), "wrong type of path value - expected number but found null");
        assertError(run("JSON.NUMINCRBY", "doc", ".nothing", "1"), "does not exist");
        assertError(run("JSON.NUMINCRBY", "doc", "$.a", "abc"), "value is not a number");
        assertError(run("JSON.NUMINCRBY", "doc", "$.a", "\"2\""), "value is not a number");
        assertError(run("JSON.NUMINCRBY", "missing", "$.a", "1"), "key that doesn't exist");
        assertEquals(OK, run("JSON.SET", "big", "$", "{\"x\":1e308,\"y\":1}"));
        assertError(run("JSON.NUMMULTBY", "big", "$.*", "10"), "finite");
        assertEquals(bulk("{\"x\":1.0E308,\"y\":1}"), run("JSON.GET", "big"), "nothing was changed by the failed command");
    }

    @Test
    void togglesBooleans() {
        assertEquals(OK, run("JSON.SET", "b", "$", "{\"t\":true,\"f\":false,\"n\":1}"));
        assertEquals(array(integer(0)), run("JSON.TOGGLE", "b", "$.t"));
        assertEquals(array(integer(1), integer(1), nil()), run("JSON.TOGGLE", "b", "$.*"), "t was false, f was false, n is no boolean");
        assertEquals(bulk("false"), run("JSON.TOGGLE", "b", ".f"), "a legacy path answers with the new value");
        assertError(run("JSON.TOGGLE", "b", ".n"), "expected boolean but found integer");
        assertError(run("JSON.TOGGLE", "missing", "$"), "key that doesn't exist");
    }

    // ---------------------------------------------------------------------------------------------- strings

    @Test
    void stringsAreAppendedToAndMeasuredInBytes() {
        assertEquals(OK, run("JSON.SET", "s", "$", "{\"a\":\"foo\",\"n\":1}"));
        assertEquals(array(integer(6)), run("JSON.STRAPPEND", "s", "$.a", "\"bar\""));
        assertEquals(integer(7), run("JSON.STRAPPEND", "s", ".a", "\"!\""));
        assertError(run("JSON.STRAPPEND", "s", "\"\""), "expected string but found object"); // no path: the root, an object here
        assertEquals(array(integer(7)), run("JSON.STRLEN", "s", "$.a"));
        assertEquals(integer(7), run("JSON.STRLEN", "s", ".a"));
        assertEquals(array(integer(7), nil()), run("JSON.STRLEN", "s", "$.*"));
        assertError(run("JSON.STRAPPEND", "s", "$.a", "nope"), "invalid JSON");
        assertError(run("JSON.STRAPPEND", "s", "$.a", "5"), "expected a string");
        assertError(run("JSON.STRLEN", "s", ".n"), "expected string but found integer");
        assertEquals(nil(), run("JSON.STRLEN", "missing"));
        assertEquals(bulk("[\"foobar!\"]"), run("JSON.GET", "s", "$.a"));
    }

    @Test
    void textIsKeptAsTheBytesTheClientSent() {
        String utf8 = new String("héllo €😀".getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
        assertEquals(OK, run("JSON.SET", "u", "$", "{\"t\":" + "\"" + utf8 + "\"" + "}"));
        assertEquals(bulk("[\"" + utf8 + "\"]"), run("JSON.GET", "u", "$.t"), "the bytes come back as they went in");
        assertEquals(array(integer(utf8.length())), run("JSON.STRLEN", "u", "$.t"), "the length is in bytes");
        assertEquals(OK, run("JSON.SET", "u", "$.escaped", "\"\\u00e9\\ud83d\\ude00\\n\""));
        assertEquals(bulk("[\"" + new String("é😀".getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1) + "\\n\"]"),
                run("JSON.GET", "u", "$.escaped"), "escapes become UTF-8 bytes and a newline is escaped again on the way out");
        assertError(run("JSON.SET", "u", "$.bad", "\"\u00c3\""), "invalid UTF-8");
        assertEquals(OK, run("JSON.SET", "u", "$[\"" + utf8 + "\"]", "1"), "a member name with non-ASCII characters");
        assertEquals(bulk("[1]"), run("JSON.GET", "u", "$['" + utf8 + "']"));
    }

    // ----------------------------------------------------------------------------------------------- arrays

    @Test
    void arraysAreAppendedInsertedSearchedPoppedAndTrimmed() {
        assertEquals(OK, run("JSON.SET", "l", "$", "{\"a\":[1],\"b\":[2],\"c\":3,\"e\":[]}"));
        assertEquals(array(integer(3)), run("JSON.ARRAPPEND", "l", "$.a", "2", "3"));
        assertEquals(integer(4), run("JSON.ARRAPPEND", "l", ".a", "{\"x\":[0]}"));
        assertEquals(array(integer(5), integer(2), nil(), integer(1)), run("JSON.ARRAPPEND", "l", "$.*", "\"s\""));
        assertEquals(array(integer(6)), run("JSON.ARRINSERT", "l", "$.a", "0", "0"));
        assertEquals(integer(7), run("JSON.ARRINSERT", "l", ".a", "-1", "\"before last\""));
        assertEquals(bulk("[[0,1,2,3,{\"x\":[0]},\"before last\",\"s\"]]"), run("JSON.GET", "l", "$.a"));
        assertError(run("JSON.ARRINSERT", "l", "$.a", "99", "1"), "index out of range");
        assertError(run("JSON.ARRINSERT", "l", "$.a", "x", "1"), "not an integer");
        assertEquals(array(integer(2)), run("JSON.ARRINDEX", "l", "$.a", "2"));
        assertEquals(array(integer(-1)), run("JSON.ARRINDEX", "l", "$.a", "2", "3"));
        assertEquals(array(integer(4)), run("JSON.ARRINDEX", "l", "$.a", "{\"x\":[0]}"));
        assertEquals(array(integer(0)), run("JSON.ARRINDEX", "l", "$.a", "0.0"), "0.0 equals 0");
        assertEquals(integer(2), run("JSON.ARRINDEX", "l", ".a", "2"));
        assertEquals(array(integer(7), integer(2), nil(), integer(1)), run("JSON.ARRLEN", "l", "$.*"));
        assertEquals(integer(7), run("JSON.ARRLEN", "l", ".a"));
        assertEquals(bulk("\"s\""), run("JSON.ARRPOP", "l", ".a"));
        assertEquals(array(bulk("0")), run("JSON.ARRPOP", "l", "$.a", "0"));
        assertEquals(array(integer(4)), run("JSON.ARRTRIM", "l", "$.a", "1", "4"));
        assertEquals(bulk("[[2,3,{\"x\":[0]},\"before last\"]]"), run("JSON.GET", "l", "$.a"));
        assertError(run("JSON.ARRAPPEND", "l", ".c", "1"), "expected array but found integer");
        assertError(run("JSON.ARRAPPEND", "l", ".nothing", "1"), "does not exist");
        assertError(run("JSON.ARRAPPEND", "l", "$.a", "{bad"), "invalid JSON");
        assertError(run("JSON.ARRAPPEND", "missing", "$", "1"), "key that doesn't exist");
        assertEquals(nil(), run("JSON.ARRLEN", "missing"));
    }

    @Test
    void popFromAnEmptyArrayAnswersNil() {
        assertEquals(OK, run("JSON.SET", "p", "$", "{\"e\":[],\"s\":\"x\"}"));
        assertEquals(array(nil()), run("JSON.ARRPOP", "p", "$.e"));
        assertEquals(nil(), run("JSON.ARRPOP", "p", ".e"));
        assertEquals(array(nil()), run("JSON.ARRPOP", "p", "$.s"));
        assertError(run("JSON.ARRPOP", "p", ".s"), "expected array but found string");
        assertError(run("JSON.ARRPOP", "missing"), "key that doesn't exist");
    }

    // ----------------------------------------------------------------------------------------------- objects

    @Test
    void objectsAreInspected() {
        seed();
        assertEquals(array(nil(), nil(), array(bulk("a"), bulk("b")), nil(), nil()), run("JSON.OBJKEYS", "doc", "$..*"),
                "the five descendants: a, b, nested, nested.a, nested.b - only nested is an object");
        assertEquals(array(array(bulk("a"), bulk("b"), bulk("nested"))), run("JSON.OBJKEYS", "doc", "$"));
        assertEquals(array(bulk("a"), bulk("b"), bulk("nested")), run("JSON.OBJKEYS", "doc"));
        assertEquals(array(integer(3)), run("JSON.OBJLEN", "doc", "$"));
        assertEquals(integer(2), run("JSON.OBJLEN", "doc", ".nested"));
        assertEquals(array(nil()), run("JSON.OBJLEN", "doc", "$.a"));
        assertError(run("JSON.OBJKEYS", "doc", ".a"), "expected object but found integer");
        assertEquals(nil(), run("JSON.OBJKEYS", "missing"));
    }

    @Test
    void clearEmptiesContainersAndZeroesNumbers() {
        assertEquals(OK, run("JSON.SET", "c", "$", "{\"o\":{\"a\":1},\"l\":[1,2],\"n\":5,\"s\":\"x\"}"));
        assertEquals(integer(3), run("JSON.CLEAR", "c", "$.*"));
        assertEquals(integer(0), run("JSON.CLEAR", "c", "$.*"));
        assertEquals(bulk("{\"o\":{},\"l\":[],\"n\":0,\"s\":\"x\"}"), run("JSON.GET", "c"));
        assertEquals(integer(0), run("JSON.CLEAR", "missing"));
        assertEquals(integer(0), run("JSON.CLEAR", "c", "$.nothing"));
    }

    @Test
    void mergePatches() {
        assertEquals(OK, run("JSON.SET", "m", "$", "{\"a\":{\"x\":1,\"y\":2},\"l\":[1,2]}"));
        assertEquals(OK, run("JSON.MERGE", "m", "$", "{\"a\":{\"x\":null,\"z\":3},\"l\":[9],\"new\":{\"deep\":true,\"gone\":null}}"));
        assertEquals(bulk("{\"a\":{\"y\":2,\"z\":3},\"l\":[9],\"new\":{\"deep\":true}}"), run("JSON.GET", "m"));
        assertEquals(OK, run("JSON.MERGE", "m", "$.a", "{\"y\":null}"));
        assertEquals(OK, run("JSON.MERGE", "m", "$.created", "{\"p\":1}"));
        assertEquals(bulk("{\"a\":{\"z\":3},\"l\":[9],\"new\":{\"deep\":true},\"created\":{\"p\":1}}"), run("JSON.GET", "m"));
        assertEquals(OK, run("JSON.MERGE", "fresh", "$", "{\"a\":1,\"b\":null}"));
        assertEquals(bulk("{\"a\":1}"), run("JSON.GET", "fresh"));
        assertError(run("JSON.MERGE", "fresh2", "$.a", "1"), "new objects must be created at the root");
        assertError(run("JSON.MERGE", "m", "$.no.parent", "1"), "does not exist");
        assertError(run("JSON.MERGE", "m", "$", "{bad"), "invalid JSON");
    }

    @Test
    void debugMemoryReportsTheEstimatedSize() {
        seed();
        long whole = ((IntegerValue) run("JSON.DEBUG", "MEMORY", "doc")).value();
        assertTrue(whole > 100 && whole < 2000, "whole " + whole);
        IntegerValue nested = (IntegerValue) run("JSON.DEBUG", "memory", "doc", ".nested");
        assertTrue(nested.value() < whole && nested.value() > 50);
        ArrayValue perMatch = (ArrayValue) run("JSON.DEBUG", "MEMORY", "doc", "$..a");
        assertEquals(2, perMatch.elements().size());
        assertEquals(integer(0), run("JSON.DEBUG", "MEMORY", "missing"));
        assertError(run("JSON.DEBUG", "HELP", "doc"), "unknown subcommand");
        assertError(run("JSON.DEBUG", "MEMORY"), "wrong number of arguments");
    }

    @Test
    void wrongArityIsReportedLikeAnyCommand() {
        assertError(run("JSON.SET", "k", "$"), "wrong number of arguments");
        assertError(run("JSON.GET"), "wrong number of arguments");
        assertError(run("JSON.ARRAPPEND", "k", "$"), "wrong number of arguments");
        assertError(run("JSON.ARRTRIM", "k", "$", "0"), "wrong number of arguments");
        assertError(run("JSON.NUMINCRBY", "k", "$"), "wrong number of arguments");
        assertError(run("JSON.MGET", "k"), "wrong number of arguments");
    }

    // ------------------------------------------------------------------------------------- memory accounting

    @Test
    void theMemoryTheStoreCountsFollowsTheDocument() {
        long before = stack.store().usedMemory();
        run("JSON.SET", "m", "$", "{\"a\":1}");
        long small = stack.store().usedMemory() - before;
        assertTrue(small > 100, "small " + small);
        StringBuilder numbers = new StringBuilder("[");
        for (int i = 0; i < 1000; i++) {
            numbers.append(i > 0 ? "," : "").append(i * 0.5);
        }
        run("JSON.SET", "m", "$.embedding", numbers.append(']').toString());
        long withEmbedding = stack.store().usedMemory() - before;
        assertTrue(withEmbedding > small + 8000 && withEmbedding < small + 14000, "a thousand numbers cost about 8 bytes each: " + (withEmbedding - small));
        for (int i = 0; i < 100; i++) {
            run("JSON.ARRAPPEND", "m", "$.embedding", "1.5");
        }
        long grown = stack.store().usedMemory() - before;
        assertTrue(grown > withEmbedding + 800 && grown < withEmbedding + 1800, "100 more numbers: " + (grown - withEmbedding));
        run("JSON.DEL", "m", "$.embedding");
        assertEquals(small, stack.store().usedMemory() - before, "deleting the member gives the bytes back exactly");
        run("JSON.DEL", "m", "$");
        assertEquals(before, stack.store().usedMemory(), "and the key gives back everything");
    }

    // --------------------------------------------------------------------------------------------- snapshot

    /** Replays what a full sync or an AOF rewrite would send into a second server. */
    private CommandHandler replicaOf(TestSupport.Stack source, TestSupport.Stack target) {
        CommandHandler targetHandler = TestSupport.allCommands(target.store(), ReplicationRole.PRIMARY);
        source.store().forEachSnapshot((SnapshotEntry entry) -> {
            for (List<String> command : entry.commands()) {
                RespValue reply = targetHandler.handle(command);
                assertFalse(reply instanceof ErrorValue, command.get(0) + " " + command.get(1) + " -> " + reply);
            }
        });
        return targetHandler;
    }

    @Test
    void aSnapshotRebuildsDocumentsAndTheirExpiry() {
        seed();
        run("JSON.SET", "big", "$", "{\"list\":[" + "1,2,3,4,5,6,7,8,9,".repeat(40_000) + "0],\"text\":\"" + "word ".repeat(100_000) + "\",\"o\":{\"k\":[true,null]}}");
        run("JSON.SET", "empty", "$", "{}");
        run("PEXPIREAT", "doc", Long.toString(System.currentTimeMillis() + 600_000));
        run("PEXPIREAT", "big", Long.toString(System.currentTimeMillis() + 600_000));
        TestSupport.Stack target = TestSupport.newStack();
        try {
            CommandHandler replica = replicaOf(stack, target);
            for (String key : List.of("doc", "big", "empty")) {
                assertEquals(run("JSON.GET", key), replica.handle(List.of("JSON.GET", key)), key);
                assertEquals(stack.store().expiresAt(key), target.store().expiresAt(key), key + " keeps its deadline");
                assertEquals("ReJSON-RL", target.store().type(key).redisName());
            }
            assertEquals(stack.store().usedMemory(), target.store().usedMemory(), "the replica counts the same bytes");
        } finally {
            target.stop();
        }
    }
}
