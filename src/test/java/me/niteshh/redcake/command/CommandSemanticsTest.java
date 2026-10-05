package me.niteshh.redcake.command;

import me.niteshh.redcake.replication.ReplicationRole;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.support.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Command behaviour compared to documented Redis semantics. */
class CommandSemanticsTest {

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

    private static void assertError(RespValue value, String contains) {
        ErrorValue error = assertInstanceOf(ErrorValue.class, value);
        assertTrue(error.message().contains(contains), error.message());
    }

    @Test
    void writeFlagComesFromTheCommandNotAHardCodedList() {
        for (String write : List.of("SET", "DEL", "EXPIRE", "PEXPIRE", "INCR", "MSET", "FLUSHALL",
                "APPEND", "RENAME", "GETDEL", "SETEX", "PERSIST", "UNLINK")) {
            assertTrue(handler.find(write).isWrite(), write);
        }
        for (String read : List.of("GET", "MGET", "PING", "ECHO", "TTL", "KEYS", "INFO", "EXISTS")) {
            assertFalse(handler.find(read).isWrite(), read);
        }
        assertNull(handler.find("NOPE"));
    }

    @Test
    void setSupportsAllOptionCombinations() {
        assertEquals(new SimpleString("OK"), run("SET", "a", "1", "EX", "100"));
        assertEquals(new NullValue(), run("SET", "a", "2", "NX"));
        assertEquals(new BulkString("1"), run("SET", "a", "3", "GET", "KEEPTTL"));
        assertEquals(new SimpleString("OK"), run("SET", "a", "4", "XX", "KEEPTTL"));
        assertTrue(((IntegerValue) run("TTL", "a")).value() > 0);
        assertEquals(new NullValue(), run("SET", "missing", "x", "XX"));
    }

    @Test
    void setRejectsBadOptions() {
        assertError(run("SET", "k", "v", "NX", "XX"), "syntax error");
        assertError(run("SET", "k", "v", "EX"), "syntax error");
        assertError(run("SET", "k", "v", "EX", "10", "PX", "5"), "syntax error");
        assertError(run("SET", "k", "v", "BOGUS"), "syntax error");
        assertError(run("SET", "k", "v", "EX", "0"), "invalid expire time");
        assertError(run("SET", "k", "v", "EX", "abc"), "not an integer");
        assertError(run("SET", "k", "v", "EX", "9223372036854775807"), "invalid expire time");
    }

    @Test
    void expireWithZeroOrNegativeDeletesTheKey() {
        run("SET", "k", "v");
        assertEquals(new IntegerValue(1), run("EXPIRE", "k", "0"));
        assertEquals(new IntegerValue(0), run("EXISTS", "k"));
        assertEquals(new IntegerValue(0), run("EXPIRE", "missing", "10"));
    }

    @Test
    void expireOptionsAndPersist() {
        run("SET", "k", "v");
        assertEquals(new IntegerValue(0), run("EXPIRE", "k", "10", "XX"));
        assertEquals(new IntegerValue(1), run("EXPIRE", "k", "10", "NX"));
        assertEquals(new IntegerValue(1), run("PERSIST", "k"));
        assertEquals(new IntegerValue(-1), run("TTL", "k"));
        assertError(run("EXPIRE", "k", "10", "WAT"), "Unsupported option");
    }

    @Test
    void relativeTimesAreRewrittenToAbsoluteForReplication() {
        long now = 1_000_000L;
        assertEquals(List.of("SET", "k", "v", "PXAT", "1010000"),
                handler.normalize(List.of("SET", "k", "v", "EX", "10"), now));
        assertEquals(List.of("SET", "k", "v", "NX", "GET", "PXAT", "1000250"),
                handler.normalize(List.of("SET", "k", "v", "GET", "NX", "PX", "250"), now));
        assertEquals(List.of("SET", "k", "v", "PXAT", "5000000"),
                handler.normalize(List.of("SET", "k", "v", "EXAT", "5000"), now));
        assertEquals(List.of("PEXPIREAT", "k", "1010000"),
                handler.normalize(List.of("EXPIRE", "k", "10"), now));
        assertEquals(List.of("PEXPIREAT", "k", "1000500", "NX"),
                handler.normalize(List.of("PEXPIRE", "k", "500", "NX"), now));
        assertEquals(List.of("PEXPIREAT", "k", "7000000"),
                handler.normalize(List.of("EXPIREAT", "k", "7000"), now));
        assertEquals(List.of("SET", "k", "v", "PXAT", "1003000"),
                handler.normalize(List.of("SETEX", "k", "3", "v"), now));
        assertEquals(List.of("SET", "k", "v", "PXAT", "1000007"),
                handler.normalize(List.of("PSETEX", "k", "7", "v"), now));
    }

    @Test
    void normalizeLeavesInvalidAndTimelessCommandsAlone() {
        List<String> plain = List.of("SET", "k", "v");
        assertSame(plain, handler.normalize(plain, 1));
        List<String> bad = List.of("EXPIRE", "k", "abc");
        assertSame(bad, handler.normalize(bad, 1));
        List<String> unknown = List.of("NOPE", "x");
        assertSame(unknown, handler.normalize(unknown, 1));
    }

    @Test
    void stringFamily() {
        assertEquals(new SimpleString("OK"), run("MSET", "a", "1", "b", "2"));
        ArrayValue values = assertInstanceOf(ArrayValue.class, run("MGET", "a", "zzz", "b"));
        assertEquals(List.of(new BulkString("1"), new NullValue(), new BulkString("2")), values.elements());
        assertError(run("MSET", "a"), "wrong number of arguments");

        assertEquals(new IntegerValue(1), run("SETNX", "n", "x"));
        assertEquals(new IntegerValue(0), run("SETNX", "n", "y"));
        assertEquals(new BulkString("x"), run("GETSET", "n", "z"));
        assertEquals(new BulkString("z"), run("GETDEL", "n"));
        assertEquals(new NullValue(), run("GET", "n"));
        assertEquals(new IntegerValue(2), run("APPEND", "ap", "hi"));
        assertEquals(new IntegerValue(2), run("STRLEN", "ap"));
        assertEquals(new SimpleString("string"), run("TYPE", "ap"));
        assertEquals(new SimpleString("none"), run("TYPE", "nope"));
    }

    @Test
    void keyspaceCommands() {
        run("MSET", "user:1", "a", "user:2", "b", "other", "c");
        assertEquals(new IntegerValue(3), run("DBSIZE"));
        ArrayValue keys = assertInstanceOf(ArrayValue.class, run("KEYS", "user:*"));
        assertEquals(2, keys.elements().size());
        assertEquals(new SimpleString("OK"), run("RENAME", "other", "renamed"));
        assertError(run("RENAME", "ghost", "x"), "no such key");
        assertEquals(new SimpleString("OK"), run("FLUSHALL"));
        assertEquals(new IntegerValue(0), run("DBSIZE"));
    }

    @Test
    void integerCommands() {
        assertEquals(new IntegerValue(1), run("INCR", "c"));
        assertEquals(new IntegerValue(11), run("INCRBY", "c", "10"));
        assertEquals(new IntegerValue(10), run("DECR", "c"));
        assertEquals(new IntegerValue(4), run("DECRBY", "c", "6"));
        assertError(run("DECRBY", "c", "-9223372036854775808"), "overflow");
        assertError(run("INCRBY", "c", "x"), "not an integer");
        run("SET", "s", "text");
        assertError(run("INCR", "s"), "not an integer");
    }

    @Test
    void compatibilityCommandsForClientLibraries() {
        assertEquals(new SimpleString("OK"), run("SELECT", "0"));
        assertError(run("SELECT", "1"), "out of range");
        org.springframework.beans.factory.ObjectProvider<CommandHandler> provider =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(provider.getObject()).thenReturn(handler);
        var commandInfo = new me.niteshh.redcake.command.commands.reads.CommandInfoCommand(provider);
        assertEquals(ArrayValue.empty(), commandInfo.execute(List.of("DOCS")));
        assertEquals(new IntegerValue(handler.commandCount()), commandInfo.execute(List.of("COUNT")));
        assertTrue(((ArrayValue) commandInfo.execute(List.of("LIST"))).elements().contains(new BulkString("hset")));
        assertError(run("HELLO", "3"), "NOPROTO");
        assertInstanceOf(ArrayValue.class, run("HELLO", "2"));
        assertInstanceOf(ArrayValue.class, run("TIME"));
    }

    @Test
    void errorsAreRepliedNotThrown() {
        assertError(run("GET"), "wrong number of arguments for 'get'");
        assertError(run("NOPE"), "unknown command 'NOPE'");
        assertError(handler.handle(List.of()), "empty command");
    }

    @Test
    void errorReplyGetsErrPrefixOnlyWhenNoCodeIsPresent() throws Exception {
        RespWriter writer = new RespWriter();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        writer.write(new ErrorValue("wrong number of arguments"), out);
        writer.write(new ErrorValue("NOAUTH authentication required"), out);
        writer.write(new ErrorValue("bad\r\nINJECT"), out);
        assertEquals(
                "-ERR wrong number of arguments\r\n"
                        + "-NOAUTH authentication required\r\n"
                        + "-ERR bad  INJECT\r\n",
                out.toString());
    }

    @Test
    void arraysEncodeNestedValues() throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        new RespWriter().write(
                new ArrayValue(List.of(new BulkString("a"), new NullValue(), new IntegerValue(7))), out);
        assertEquals("*3\r\n$1\r\na\r\n$-1\r\n:7\r\n", out.toString());
    }
}
