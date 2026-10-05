package me.niteshh.redcake.server;

import me.niteshh.redcake.config.MemoryConfig;
import me.niteshh.redcake.support.RespClient;
import me.niteshh.redcake.support.TestServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** maxmemory enforcement: refusal, the three policies, and propagation of evictions. */
class MemoryLimitTest {

    private TestServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private static final String VALUE = "x".repeat(200);

    @Test
    void noevictionRefusesGrowingWritesButStillAllowsReadsAndDeletes() throws Exception {
        server = new TestServer().start();
        server.memoryConfig.setMaxBytes(2_000);
        try (RespClient c = server.client()) {
            int stored = 0;
            while (stored < 100 && c.call("SET", "k" + stored, VALUE).equals("+OK")) {
                stored++;
            }
            assertTrue(stored > 0 && stored < 100, "some, but not all writes fit: " + stored);

            assertTrue(c.call("SET", "one-more", VALUE).startsWith("-OOM"));
            assertTrue(c.call("LPUSH", "list", "x").startsWith("-OOM"), "every growing command is refused");
            assertEquals("\"" + VALUE + "\"", c.call("GET", "k0"), "reads keep working");
            assertEquals(":1", c.call("DEL", "k0"), "deletes are always allowed");
            assertEquals(":1", c.call("EXPIRE", "k1", "100"), "commands that cannot grow memory are allowed");
            assertEquals("+OK", c.call("SET", "fits-again", "y"), "freeing memory lets writes through again");
        }
    }

    @Test
    void allkeysRandomEvictsToStayUnderTheLimit() throws Exception {
        server = new TestServer().start();
        server.memoryConfig.setMaxBytes(4_000);
        server.memoryConfig.setPolicy(MemoryConfig.Policy.ALLKEYS_RANDOM);
        try (RespClient c = server.client()) {
            for (int i = 0; i < 200; i++) {
                assertEquals("+OK", c.call("SET", "k" + i, VALUE), "no OOM while eviction is possible");
            }
            assertTrue(server.stack.store().usedMemory() <= 4_000 + 400,
                    "memory must hover near the limit: " + server.stack.store().usedMemory());
            assertTrue(server.stack.store().size() < 200);
            assertTrue(c.call("INFO", "stats").contains("evicted_keys:"));
            assertTrue(server.serverStats.evictedKeys() > 0);
        }
    }

    @Test
    void volatileTtlEvictsKeysWithTtlFirstAndSparesPersistentOnes() throws Exception {
        server = new TestServer().start();
        try (RespClient c = server.client()) {
            for (int i = 0; i < 5; i++) {
                assertEquals("+OK", c.call("SET", "keep" + i, VALUE));
            }
            for (int i = 0; i < 5; i++) {
                assertEquals("+OK", c.call("SET", "temp" + i, VALUE, "EX", Integer.toString(100 + i)));
            }
            long inUse = server.stack.store().usedMemory();
            server.memoryConfig.setMaxBytes(inUse - 600); // force ~2-3 evictions
            server.memoryConfig.setPolicy(MemoryConfig.Policy.VOLATILE_TTL);

            assertEquals("+OK", c.call("SET", "trigger", "y"));
            assertEquals("\"" + VALUE + "\"", c.call("GET", "keep0"), "persistent keys must survive");
            assertEquals("(nil)", c.call("GET", "temp0"), "the soonest-expiring key goes first");
            assertTrue(server.serverStats.evictedKeys() >= 1);
        }
    }

    @Test
    void volatileTtlRefusesWritesWhenNothingHasATtl() throws Exception {
        server = new TestServer().start();
        server.memoryConfig.setMaxBytes(1_500);
        server.memoryConfig.setPolicy(MemoryConfig.Policy.VOLATILE_TTL);
        try (RespClient c = server.client()) {
            int stored = 0;
            while (stored < 50 && c.call("SET", "k" + stored, VALUE).equals("+OK")) {
                stored++;
            }
            assertTrue(stored < 50);
            assertTrue(c.call("SET", "more", VALUE).startsWith("-OOM"));
        }
    }

    /** A replica must delete exactly the keys the primary evicted. */
    @Test
    void evictionsAreSentToReplicasAsExplicitDels() throws Exception {
        server = new TestServer().start();
        server.memoryConfig.setMaxBytes(3_000);
        server.memoryConfig.setPolicy(MemoryConfig.Policy.ALLKEYS_RANDOM);

        try (RespClient link = server.client(); RespClient c = server.client()) {
            link.send("PSYNC", "?", "-1");
            link.read(); // FULLRESYNC
            link.read(); // SYNCED
            link.setTimeout(5_000);

            for (int i = 0; i < 40; i++) {
                c.call("SET", "k" + i, VALUE);
            }
            boolean sawDel = false;
            for (int i = 0; i < 100 && !sawDel; i++) {
                String command = link.read();
                sawDel = command.startsWith("[\"DEL\"");
            }
            assertTrue(sawDel, "an eviction must appear in the replication stream as DEL");
        }
    }

    @Test
    void sizeParsingAcceptsUnits() {
        assertEquals(1024, MemoryConfig.parseSize("1k"));
        assertEquals(100L * 1024 * 1024, MemoryConfig.parseSize("100mb"));
        assertEquals(2L * 1024 * 1024 * 1024, MemoryConfig.parseSize("2G"));
        assertEquals(12345, MemoryConfig.parseSize("12345"));
        assertThrows(IllegalArgumentException.class, () -> MemoryConfig.parseSize("lots"));
        assertThrows(IllegalArgumentException.class, () -> MemoryConfig.parseSize("-5"));
        assertThrows(IllegalArgumentException.class, () -> MemoryConfig.Policy.parse("lru-ish"));
    }
}
