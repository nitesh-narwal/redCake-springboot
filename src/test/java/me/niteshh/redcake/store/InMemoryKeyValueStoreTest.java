package me.niteshh.redcake.store;

import me.niteshh.redcake.support.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryKeyValueStoreTest {

    private TestSupport.Stack stack;
    private InMemoryKeyValueStore store;

    @BeforeEach
    void setUp() {
        stack = TestSupport.newStack();
        store = stack.store();
    }

    @AfterEach
    void tearDown() {
        stack.stop();
    }

    @Test
    void setNxOnlyWritesWhenAbsent() {
        SetOptions nx = new SetOptions(null, false, SetOptions.Condition.ONLY_IF_ABSENT, false);
        assertTrue(store.set("k", "1", nx).applied());
        assertFalse(store.set("k", "2", nx).applied());
        assertEquals("1", store.get("k"));
    }

    @Test
    void setXxOnlyWritesWhenPresent() {
        SetOptions xx = new SetOptions(null, false, SetOptions.Condition.ONLY_IF_PRESENT, false);
        assertFalse(store.set("k", "1", xx).applied());
        assertNull(store.get("k"));
        store.set("k", "0");
        assertTrue(store.set("k", "1", xx).applied());
    }

    @Test
    void setGetReturnsPreviousValue() {
        store.set("k", "old");
        SetResult result = store.set("k", "new",
                new SetOptions(null, false, SetOptions.Condition.ALWAYS, true));
        assertEquals("old", result.previousValue());
        assertEquals("new", store.get("k"));
    }

    @Test
    void keepTtlPreservesDeadlineAndPlainSetClearsIt() {
        long deadline = System.currentTimeMillis() + 60_000;
        store.set("k", "a", deadline);
        store.set("k", "b", new SetOptions(null, true, SetOptions.Condition.ALWAYS, false));
        assertTrue(store.pttl("k") > 0);

        store.set("k", "c");
        assertEquals(-1, store.pttl("k"));
    }

    @Test
    void expireInThePastDeletesTheKey() {
        store.set("k", "v");
        assertTrue(store.expire("k", System.currentTimeMillis() - 1));
        assertFalse(store.exists("k"));
    }

    @Test
    void expireConditionsFollowRedisSemantics() {
        long now = System.currentTimeMillis();
        store.set("k", "v");

        assertFalse(store.expire("k", now + 1_000, ExpireCondition.XX)); // no TTL yet
        assertTrue(store.expire("k", now + 10_000, ExpireCondition.NX));
        assertFalse(store.expire("k", now + 20_000, ExpireCondition.NX)); // already has TTL
        assertTrue(store.expire("k", now + 20_000, ExpireCondition.GT));
        assertFalse(store.expire("k", now + 5_000, ExpireCondition.GT));
        assertTrue(store.expire("k", now + 5_000, ExpireCondition.LT));
    }

    @Test
    void ttlRoundsToNearestSecond() {
        store.set("k", "v", System.currentTimeMillis() + 1_400);
        assertEquals(1, store.ttl("k"));
        store.set("j", "v", System.currentTimeMillis() + 1_700);
        assertEquals(2, store.ttl("j"));
    }

    @Test
    void persistRemovesTtl() {
        store.set("k", "v", System.currentTimeMillis() + 60_000);
        assertTrue(store.persist("k"));
        assertEquals(-1, store.ttl("k"));
        assertFalse(store.persist("k"));
        assertEquals(0, stack.expirationManager().size());
    }

    @Test
    void incrementKeepsTtlAndStillExpires() throws Exception {
        store.set("n", "5", System.currentTimeMillis() + 150);
        assertEquals(6, store.increment("n", 1));
        assertTrue(store.pttl("n") > 0, "INCR must not drop the TTL");
        assertTrue(TestSupport.await(2_000, () -> store.get("n") == null),
                "key must be reaped after its TTL");
    }

    @Test
    void incrementOnExpiredKeyStartsFromZeroWithoutTtl() throws Exception {
        store.set("n", "5", System.currentTimeMillis() + 30);
        Thread.sleep(80);
        assertEquals(1, store.increment("n", 1));
        assertEquals(-1, store.pttl("n"));
    }

    @Test
    void incrementRejectsNonIntegersAndOverflowWithoutChangingValue() {
        store.set("s", "abc");
        assertThrows(InvalidIntegerException.class, () -> store.increment("s", 1));
        assertEquals("abc", store.get("s"));

        store.set("max", Long.toString(Long.MAX_VALUE));
        assertThrows(InvalidIntegerException.class, () -> store.increment("max", 1));
        assertEquals(Long.toString(Long.MAX_VALUE), store.get("max"));
    }

    @Test
    void appendRenameAndKeysWork() {
        assertEquals(3, store.append("a", "abc"));
        // Values are byte strings: one char is one byte, so length == byte length.
        assertEquals(5, store.append("a", "d\u00e9"));
        assertEquals("abcd\u00e9", store.get("a"));

        store.set("src", "v", System.currentTimeMillis() + 60_000);
        assertTrue(store.rename("src", "dst"));
        assertNull(store.get("src"));
        assertTrue(store.pttl("dst") > 0, "RENAME must keep the TTL");
        assertFalse(store.rename("missing", "x"));

        store.set("user:1", "x");
        store.set("user:2", "y");
        assertEquals(2, store.keys("user:*").size());
    }

    @Test
    void clearRemovesKeysAndSchedules() {
        store.set("a", "1", System.currentTimeMillis() + 60_000);
        store.set("b", "2");
        store.clear();
        assertEquals(0, store.size());
        assertEquals(0, stack.expirationManager().size());
    }

    /** Regression B8: schedule size must follow the number of keys, not the number of writes. */
    @Test
    void repeatedTtlWritesDoNotAccumulateExpirationEntries() {
        long deadline = System.currentTimeMillis() + 3_600_000;
        for (int i = 0; i < 5_000; i++) {
            store.set("same-key", "v" + i, deadline + i);
            store.expire("same-key", deadline + 10_000 + i);
        }
        assertEquals(1, stack.expirationManager().size());
    }

    /** A stale expiration event must never delete a newer value of the same key. */
    @Test
    void staleExpirationEventDoesNotDeleteNewerValue() {
        store.set("k", "old", System.currentTimeMillis() + 60_000);
        store.set("k", "new");
        store.expireIfVersionMatches(new ExpirationEntry("k", System.currentTimeMillis() + 60_000, 1));
        assertEquals("new", store.get("k"));
    }

    /** Regression B4: INCR racing with EXPIRE must not lose the TTL or an increment. */
    @Test
    void concurrentIncrAndExpireLoseNothing() throws Exception {
        store.set("race", "0");
        int threads = 8;
        int perThread = 2_000;
        long deadline = System.currentTimeMillis() + 600_000;
        CountDownLatch go = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final boolean incrementer = t % 2 == 0;
                futures.add(pool.submit(() -> {
                    go.await();
                    for (int i = 0; i < perThread; i++) {
                        if (incrementer) {
                            store.increment("race", 1);
                        } else {
                            store.expire("race", deadline);
                        }
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        }

        assertEquals(Long.toString((long) (threads / 2) * perThread), store.get("race"));
        assertTrue(store.pttl("race") > 0, "EXPIRE was lost by a concurrent INCR");
    }
}
