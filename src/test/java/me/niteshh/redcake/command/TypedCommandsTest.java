package me.niteshh.redcake.command;

import me.niteshh.redcake.replication.ReplicationRole;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.store.SnapshotEntry;
import me.niteshh.redcake.support.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Hash / list / set / sorted-set commands, WRONGTYPE handling and typed snapshots. */
class TypedCommandsTest {

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

    @Test
    void hashes() {
        assertEquals(new IntegerValue(2), run("HSET", "h", "a", "1", "b", "2"));
        assertEquals(new IntegerValue(0), run("HSET", "h", "a", "9")); // update, not new
        assertEquals(new BulkString("9"), run("HGET", "h", "a"));
        assertEquals(new NullValue(), run("HGET", "h", "missing"));
        assertEquals(List.of("9", "(nil)", "2"), strings(run("HMGET", "h", "a", "x", "b")));
        assertEquals(List.of("a", "9", "b", "2"), strings(run("HGETALL", "h")));
        assertEquals(List.of("a", "b"), strings(run("HKEYS", "h")));
        assertEquals(new IntegerValue(2), run("HLEN", "h"));
        assertEquals(new IntegerValue(1), run("HEXISTS", "h", "a"));
        assertEquals(new IntegerValue(0), run("HSETNX", "h", "a", "x"));
        assertEquals(new IntegerValue(1), run("HSETNX", "h", "c", "3"));
        assertEquals(new IntegerValue(12), run("HINCRBY", "h", "a", "3"));
        assertError(run("HINCRBY", "h", "a", "x"), "not an integer");
        run("HSET", "h", "text", "abc");
        assertError(run("HINCRBY", "h", "text", "1"), "hash value is not an integer");
        assertEquals(new IntegerValue(3), run("HDEL", "h", "a", "b", "zzz", "c"));
        assertEquals(new IntegerValue(1), run("HLEN", "h")); // only "text" is left
        assertError(run("HSET", "h", "only-field"), "wrong number of arguments");
    }

    @Test
    void emptyCollectionsDisappear() {
        run("RPUSH", "l", "x");
        run("SADD", "s", "x");
        run("HSET", "h", "f", "v");
        run("ZADD", "z", "1", "m");
        run("LPOP", "l");
        run("SREM", "s", "x");
        run("HDEL", "h", "f");
        run("ZREM", "z", "m");
        for (String key : List.of("l", "s", "h", "z")) {
            assertEquals(new IntegerValue(0), run("EXISTS", key), key + " must be removed when empty");
            assertEquals(new SimpleString("none"), run("TYPE", key));
        }
    }

    @Test
    void lists() {
        assertEquals(new IntegerValue(3), run("RPUSH", "l", "a", "b", "c"));
        assertEquals(new IntegerValue(5), run("LPUSH", "l", "y", "x")); // pushes one by one => x y a b c
        assertEquals(List.of("x", "y", "a", "b", "c"), strings(run("LRANGE", "l", "0", "-1")));
        assertEquals(List.of("y", "a"), strings(run("LRANGE", "l", "1", "2")));
        assertEquals(new BulkString("c"), run("LINDEX", "l", "-1"));
        assertEquals(new NullValue(), run("LINDEX", "l", "99"));
        assertEquals(new SimpleString("OK"), run("LSET", "l", "0", "X"));
        assertError(run("LSET", "l", "99", "z"), "index out of range");
        assertError(run("LSET", "nolist", "0", "z"), "no such key");
        assertEquals(new BulkString("X"), run("LPOP", "l"));
        assertEquals(new BulkString("c"), run("RPOP", "l"));
        assertEquals(List.of("y", "a"), strings(run("LPOP", "l", "2")));
        assertEquals(new IntegerValue(1), run("LLEN", "l"));
        run("RPUSH", "r", "a", "b", "a", "c", "a");
        assertEquals(new IntegerValue(2), run("LREM", "r", "2", "a"));
        assertEquals(List.of("b", "c", "a"), strings(run("LRANGE", "r", "0", "-1")));
        run("LTRIM", "r", "1", "-1");
        assertEquals(List.of("c", "a"), strings(run("LRANGE", "r", "0", "-1")));
        assertEquals(new NullValue(), run("LPOP", "missing"));
    }

    @Test
    void sets() {
        assertEquals(new IntegerValue(3), run("SADD", "a", "1", "2", "3"));
        assertEquals(new IntegerValue(1), run("SADD", "a", "3", "4"));
        run("SADD", "b", "3", "4", "5");
        assertEquals(new IntegerValue(4), run("SCARD", "a"));
        assertEquals(new IntegerValue(1), run("SISMEMBER", "a", "1"));
        assertEquals(new IntegerValue(0), run("SISMEMBER", "a", "9"));
        assertEquals(List.of("3", "4"), strings(run("SINTER", "a", "b")));
        assertEquals(List.of("1", "2", "3", "4", "5"), strings(run("SUNION", "a", "b")));
        assertEquals(List.of("1", "2"), strings(run("SDIFF", "a", "b")));
        assertEquals(List.of("1", "2", "3", "4"), strings(run("SMEMBERS", "a")));
        assertEquals(new IntegerValue(2), run("SREM", "a", "1", "2", "nope"));
        assertEquals(List.of(), strings(run("SMEMBERS", "none")));
    }

    @Test
    void sortedSets() {
        assertEquals(new IntegerValue(3), run("ZADD", "z", "1", "a", "3", "c", "2", "b"));
        assertEquals(new IntegerValue(0), run("ZADD", "z", "5", "a")); // re-score, not new
        assertEquals(new IntegerValue(1), run("ZADD", "z", "CH", "6", "a")); // CH counts changes
        assertEquals(new IntegerValue(0), run("ZADD", "z", "NX", "9", "a"));
        assertEquals(new IntegerValue(0), run("ZADD", "z", "XX", "9", "nope"));
        assertEquals(List.of("b", "c", "a"), strings(run("ZRANGE", "z", "0", "-1")));
        assertEquals(List.of("a", "c", "b"), strings(run("ZREVRANGE", "z", "0", "-1")));
        assertEquals(List.of("b", "2", "c", "3"), strings(run("ZRANGE", "z", "0", "1", "WITHSCORES")));
        assertEquals(new BulkString("6"), run("ZSCORE", "z", "a"));
        assertEquals(new IntegerValue(0), run("ZRANK", "z", "b"));
        assertEquals(new NullValue(), run("ZRANK", "z", "nope"));
        assertEquals(new IntegerValue(3), run("ZCARD", "z"));
        assertEquals(new BulkString("4.5"), run("ZINCRBY", "z", "1.5", "c"));
        assertEquals(List.of("b", "c"), strings(run("ZRANGEBYSCORE", "z", "-inf", "5")));
        assertEquals(List.of("c"), strings(run("ZRANGEBYSCORE", "z", "(2", "5")));
        assertEquals(List.of("c", "4.5"), strings(run("ZRANGEBYSCORE", "z", "(2", "5", "WITHSCORES")));
        assertEquals(List.of("c", "a"), strings(run("ZRANGEBYSCORE", "z", "3", "+inf", "LIMIT", "0", "5")));
        assertEquals(new IntegerValue(2), run("ZCOUNT", "z", "2", "5"));
        assertEquals(List.of("b", "2"), strings(run("ZPOPMIN", "z")));
        assertEquals(List.of("a", "6"), strings(run("ZPOPMAX", "z")));
        assertEquals(new IntegerValue(1), run("ZREM", "z", "c"));
        assertError(run("ZADD", "z", "abc", "m"), "not a valid float");
        assertError(run("ZADD", "z", "1"), "wrong number of arguments");
    }

    @Test
    void wrongTypeIsReportedAndLeavesTheKeyAlone() {
        run("SET", "str", "v");
        run("HSET", "hash", "f", "v");
        run("RPUSH", "list", "x");

        assertError(run("GET", "hash"), "WRONGTYPE");
        assertError(run("HGET", "str", "f"), "WRONGTYPE");
        assertError(run("LPUSH", "hash", "x"), "WRONGTYPE");
        assertError(run("SADD", "list", "x"), "WRONGTYPE");
        assertError(run("ZADD", "str", "1", "m"), "WRONGTYPE");
        assertError(run("INCR", "hash"), "WRONGTYPE");
        assertError(run("APPEND", "list", "x"), "WRONGTYPE");
        assertError(run("STRLEN", "hash"), "WRONGTYPE");
        assertError(run("SET", "hash", "v", "GET"), "WRONGTYPE");

        assertEquals(new SimpleString("hash"), run("TYPE", "hash"));
        assertEquals(new BulkString("v"), run("HGET", "hash", "f"), "failed command must not modify the key");
        // MGET reports non-strings as nil instead of failing, like Redis.
        assertEquals(new NullValue(), assertInstanceOf(ArrayValue.class, run("MGET", "hash")).elements().getFirst());
        // A plain SET overwrites any type.
        assertEquals(new SimpleString("OK"), run("SET", "hash", "now-a-string"));
        assertEquals(new SimpleString("string"), run("TYPE", "hash"));
    }

    @Test
    void genericKeyCommandsWorkOnEveryType() {
        run("HSET", "h", "f", "v");
        assertEquals(new IntegerValue(1), run("EXPIRE", "h", "100"));
        assertTrue(((IntegerValue) run("TTL", "h")).value() > 0);
        assertEquals(new SimpleString("OK"), run("RENAME", "h", "h2"));
        assertEquals(new BulkString("v"), run("HGET", "h2", "f"));
        assertTrue(((IntegerValue) run("TTL", "h2")).value() > 0, "RENAME keeps the TTL of collections");
        assertEquals(new IntegerValue(1), run("DEL", "h2"));
        assertEquals(new IntegerValue(0), run("EXISTS", "h2"));
    }

    /** Typed snapshots must be rebuildable by replaying toCommands() - this is what replication and AOF rely on. */
    @Test
    void snapshotCommandsRebuildEveryType() {
        run("SET", "s", "string");
        run("HSET", "h", "a", "1", "b", "2");
        run("RPUSH", "l", "x", "y", "z");
        run("SADD", "set", "m1", "m2");
        run("ZADD", "z", "1.5", "a", "2", "b");
        run("EXPIRE", "h", "1000");
        // many elements: forces chunking into several commands
        List<String> many = new ArrayList<>(List.of("RPUSH", "big"));
        for (int i = 0; i < 700; i++) {
            many.add("e" + i);
        }
        handler.handle(many);

        TestSupport.Stack copy = TestSupport.newStack();
        try {
            CommandHandler replay = TestSupport.allCommands(copy.store(), ReplicationRole.PRIMARY);
            List<SnapshotEntry> entries = new ArrayList<>();
            stack.store().forEachSnapshot(entries::add);
            int commandCount = 0;
            for (SnapshotEntry entry : entries) {
                for (List<String> command : entry.toCommands()) {
                    assertTrue(command.size() <= 600, "chunks must stay below the parser limit");
                    replay.handle(command);
                    commandCount++;
                }
            }
            assertTrue(commandCount > entries.size(), "the big list must be split in several commands");

            assertEquals("string", copy.store().get("s"));
            assertEquals(strings(run("HGETALL", "h")), strings(replay.handle(List.of("HGETALL", "h"))));
            assertEquals(strings(run("LRANGE", "l", "0", "-1")), strings(replay.handle(List.of("LRANGE", "l", "0", "-1"))));
            assertEquals(700, ((IntegerValue) replay.handle(List.of("LLEN", "big"))).value());
            assertEquals(strings(run("ZRANGE", "z", "0", "-1", "WITHSCORES")),
                    strings(replay.handle(List.of("ZRANGE", "z", "0", "-1", "WITHSCORES"))));
            assertEquals(2, ((IntegerValue) replay.handle(List.of("SCARD", "set"))).value());
            assertEquals(stack.store().pttl("h") / 1000, copy.store().pttl("h") / 1000, "TTL must survive");
        } finally {
            copy.stop();
        }
    }

    @Test
    void snapshotIsAnIndependentCopy() {
        run("RPUSH", "l", "a");
        List<SnapshotEntry> entries = new ArrayList<>();
        stack.store().forEachSnapshot(entries::add);
        run("RPUSH", "l", "b"); // mutate AFTER the snapshot
        assertEquals(List.of("a"), entries.getFirst().value());
    }

    @Test
    void typedCommandsAreWriteCommandsWithMemoryFlags() {
        for (String write : List.of("HSET", "LPUSH", "SADD", "ZADD", "HINCRBY", "ZINCRBY", "LSET")) {
            assertTrue(handler.find(write).isWrite() && handler.find(write).growsMemory(), write);
        }
        for (String shrink : List.of("HDEL", "LPOP", "SREM", "ZREM", "LTRIM", "ZPOPMIN")) {
            assertTrue(handler.find(shrink).isWrite() && !handler.find(shrink).growsMemory(), shrink);
        }
        for (String read : List.of("HGET", "LRANGE", "SMEMBERS", "ZRANGE", "GETRANGE", "SINTER")) {
            assertFalse(handler.find(read).isWrite(), read);
        }
    }

    @Test
    void extraStringCommands() {
        run("SET", "k", "Hello World");
        assertEquals(new BulkString("Hello"), run("GETRANGE", "k", "0", "4"));
        assertEquals(new BulkString("World"), run("GETRANGE", "k", "-5", "-1"));
        assertEquals(new BulkString(""), run("GETRANGE", "missing", "0", "5"));
        assertEquals(new IntegerValue(1), run("MSETNX", "n1", "a", "n2", "b"));
        assertEquals(new IntegerValue(0), run("MSETNX", "n3", "c", "n1", "x"));
        assertEquals(new NullValue(), run("GET", "n3"), "MSETNX must set nothing if any key exists");
        run("SET", "t", "v", "EX", "100");
        long expireTime = ((IntegerValue) run("EXPIRETIME", "t")).value();
        assertTrue(Math.abs(expireTime - (System.currentTimeMillis() / 1000 + 100)) <= 2);
        assertEquals(new IntegerValue(-1), run("EXPIRETIME", "k"));
        assertEquals(new IntegerValue(-2), run("EXPIRETIME", "missing"));
    }
}
