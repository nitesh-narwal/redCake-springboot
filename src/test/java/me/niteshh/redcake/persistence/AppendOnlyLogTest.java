package me.niteshh.redcake.persistence;

import me.niteshh.redcake.command.CommandHandler;
import me.niteshh.redcake.config.PersistenceConfig;
import me.niteshh.redcake.replication.ReplicationConfig;
import me.niteshh.redcake.replication.ReplicationRole;
import me.niteshh.redcake.replication.RespCommandCodec;
import me.niteshh.redcake.support.TestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AppendOnlyLogTest {

    @TempDir
    Path dir;

    private AppendOnlyLog open(TestSupport.Stack stack, Path file, ReplicationRole role) throws Exception {
        CommandHandler handler = TestSupport.allCommands(stack.store(), role);
        AppendOnlyLog log = new AppendOnlyLog(
                new PersistenceConfig(file, PersistenceConfig.Fsync.ALWAYS),
                handler, stack.store(),
                new ReplicationConfig(role, role == ReplicationRole.REPLICA ? "127.0.0.1" : null, 6379));
        log.open();
        return log;
    }

    /** Runs a command on the store and logs the normalized form, like ClientHandler. */
    private void write(TestSupport.Stack stack, AppendOnlyLog log, String... command) throws Exception {
        CommandHandler handler = TestSupport.allCommands(stack.store(), ReplicationRole.PRIMARY);
        List<String> normalized = handler.normalize(List.of(command), System.currentTimeMillis());
        handler.handle(normalized);
        log.append(normalized);
    }

    @Test
    void replayRebuildsTheDatasetAfterARestart() throws Exception {
        Path file = dir.resolve("redcake.aof");
        TestSupport.Stack first = TestSupport.newStack();
        AppendOnlyLog log = open(first, file, ReplicationRole.PRIMARY);
        write(first, log, "SET", "a", "1");
        write(first, log, "INCR", "a");
        write(first, log, "SET", "ttl", "x", "EX", "600");
        write(first, log, "SET", "gone", "x");
        write(first, log, "DEL", "gone");
        log.close();
        first.stop();

        TestSupport.Stack second = TestSupport.newStack();
        AppendOnlyLog reopened = open(second, file, ReplicationRole.PRIMARY);
        try {
            assertEquals("2", second.store().get("a"));
            assertNull(second.store().get("gone"));
            long ttl = second.store().pttl("ttl");
            assertTrue(ttl > 590_000 && ttl <= 600_000, "TTL must survive as an absolute deadline: " + ttl);
        } finally {
            reopened.close();
            second.stop();
        }
    }

    @Test
    void expiredKeysAreNotResurrectedByReplay() throws Exception {
        Path file = dir.resolve("redcake.aof");
        Files.write(file, RespCommandCodec.encode(
                List.of("SET", "old", "v", "PXAT", Long.toString(System.currentTimeMillis() - 1_000))));

        TestSupport.Stack stack = TestSupport.newStack();
        AppendOnlyLog log = open(stack, file, ReplicationRole.PRIMARY);
        try {
            assertNull(stack.store().get("old"));
        } finally {
            log.close();
            stack.stop();
        }
    }

    @Test
    void tornTailIsTruncatedAndLaterAppendsStayReadable() throws Exception {
        Path file = dir.resolve("redcake.aof");
        byte[] good = RespCommandCodec.encode(List.of("SET", "k", "v"));
        byte[] torn = "*3\r\n$3\r\nSET\r\n$1\r\nx".getBytes();
        Files.write(file, concat(good, torn));

        TestSupport.Stack stack = TestSupport.newStack();
        AppendOnlyLog log = open(stack, file, ReplicationRole.PRIMARY);
        assertEquals("v", stack.store().get("k"));
        assertEquals(good.length, Files.size(file), "damaged tail must be cut off");

        write(stack, log, "SET", "after", "crash");
        log.close();
        stack.stop();

        TestSupport.Stack third = TestSupport.newStack();
        AppendOnlyLog again = open(third, file, ReplicationRole.PRIMARY);
        try {
            assertEquals("v", third.store().get("k"));
            assertEquals("crash", third.store().get("after"));
        } finally {
            again.close();
            third.stop();
        }
    }

    @Test
    void rewriteCompactsTheLogWithoutChangingTheData() throws Exception {
        Path file = dir.resolve("redcake.aof");
        TestSupport.Stack stack = TestSupport.newStack();
        AppendOnlyLog log = open(stack, file, ReplicationRole.PRIMARY);
        for (int i = 0; i < 500; i++) {
            write(stack, log, "INCR", "counter");
        }
        write(stack, log, "SET", "keep", "me");
        long before = Files.size(file);

        assertEquals(2, log.rewrite());
        assertTrue(Files.size(file) < before / 10, "rewritten log should be tiny");

        write(stack, log, "INCR", "counter"); // appends must keep working after the swap
        log.close();
        stack.stop();

        TestSupport.Stack replayed = TestSupport.newStack();
        AppendOnlyLog reopened = open(replayed, file, ReplicationRole.PRIMARY);
        try {
            assertEquals("501", replayed.store().get("counter"));
            assertEquals("me", replayed.store().get("keep"));
        } finally {
            reopened.close();
            replayed.stop();
        }
    }

    @Test
    void disabledOrReplicaModeNeverTouchesTheDisk() throws Exception {
        AppendOnlyLog disabled = AppendOnlyLog.disabled();
        disabled.open();
        assertFalse(disabled.isActive());
        disabled.append(List.of("SET", "a", "b")); // silently ignored

        Path file = dir.resolve("replica.aof");
        TestSupport.Stack stack = TestSupport.newStack();
        AppendOnlyLog onReplica = open(stack, file, ReplicationRole.REPLICA);
        try {
            assertFalse(onReplica.isActive());
            assertFalse(Files.exists(file));
        } finally {
            stack.stop();
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Test
    void everyDataTypeSurvivesReplayAndRewrite() throws Exception {
        Path file = dir.resolve("types.aof");
        TestSupport.Stack first = TestSupport.newStack();
        AppendOnlyLog log = open(first, file, ReplicationRole.PRIMARY);
        write(first, log, "HSET", "h", "a", "1", "b", "2");
        write(first, log, "RPUSH", "l", "x", "y", "z");
        write(first, log, "SADD", "s", "m1", "m2");
        write(first, log, "ZADD", "z", "1.5", "a", "2", "b");
        write(first, log, "SET", "str", "v");
        write(first, log, "EXPIRE", "h", "1000");
        write(first, log, "LPOP", "l");
        write(first, log, "HDEL", "h", "b");

        // compaction must turn the history into one command set per key and keep everything
        assertEquals(5, log.rewrite());
        write(first, log, "RPUSH", "l", "after-rewrite");
        log.close();
        first.stop();

        TestSupport.Stack second = TestSupport.newStack();
        AppendOnlyLog reopened = open(second, file, ReplicationRole.PRIMARY);
        try {
            CommandHandler handler = TestSupport.allCommands(second.store(), ReplicationRole.PRIMARY);
            assertEquals("v", second.store().get("str"));
            assertEquals(List.of("a", "1"),
                    ((me.niteshh.redcake.resp.ArrayValue) handler.handle(List.of("HGETALL", "h"))).elements().stream()
                            .map(e -> ((me.niteshh.redcake.resp.BulkString) e).value()).toList());
            assertEquals(3, ((me.niteshh.redcake.resp.IntegerValue) handler.handle(List.of("LLEN", "l"))).value());
            assertEquals(2, ((me.niteshh.redcake.resp.IntegerValue) handler.handle(List.of("SCARD", "s"))).value());
            assertEquals(2, ((me.niteshh.redcake.resp.IntegerValue) handler.handle(List.of("ZCARD", "z"))).value());
            assertTrue(second.store().pttl("h") > 900_000, "the hash must keep its TTL");
        } finally {
            reopened.close();
            second.stop();
        }
    }

    @Test
    void aPromotedReplicaStartsLoggingFromItsCurrentDataset() throws Exception {
        Path file = dir.resolve("promoted.aof");
        TestSupport.Stack stack = TestSupport.newStack();
        AppendOnlyLog log = open(stack, file, ReplicationRole.REPLICA); // inactive on a replica
        assertFalse(log.isActive());
        stack.store().set("from-primary", "v");

        log.activateFromSnapshot();
        assertTrue(log.isActive());
        write(stack, log, "SET", "after-promotion", "w");
        log.deactivate(); // demotion: stop logging again
        assertFalse(log.isActive());
        stack.stop();

        TestSupport.Stack replayed = TestSupport.newStack();
        AppendOnlyLog again = open(replayed, file, ReplicationRole.PRIMARY);
        try {
            assertEquals("v", replayed.store().get("from-primary"));
            assertEquals("w", replayed.store().get("after-promotion"));
        } finally {
            again.close();
            replayed.stop();
        }
    }
}
