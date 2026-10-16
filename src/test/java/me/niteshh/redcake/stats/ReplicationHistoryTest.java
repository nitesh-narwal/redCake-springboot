package me.niteshh.redcake.stats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** PSYNC2 bookkeeping: what a promoted node remembers so the old primary's replicas can continue from it. */
class ReplicationHistoryTest {

    @Test
    void aFreshNodeOnlyKnowsItsOwnHistory() {
        ReplicationStats stats = new ReplicationStats();

        assertEquals("", stats.replid2());
        assertEquals(-1, stats.secondReplidOffset());
        assertTrue(stats.continuesHistory(stats.replid(), 0));
        assertFalse(stats.continuesHistory("f".repeat(40), 0));
    }

    @Test
    void promotionKeepsTheOldIdValidUpToTheOffsetItHadReached() {
        ReplicationStats stats = new ReplicationStats();
        String old = "a".repeat(40);
        stats.adopt(old, 1_000);
        stats.advance(500);

        stats.shiftReplicationId();

        assertNotEquals(old, stats.replid(), "a promotion starts a new history");
        assertEquals(old, stats.replid2());
        assertEquals(1_500, stats.secondReplidOffset());
        assertEquals(1_500, stats.offset(), "the new stream continues the old one; the offset is not reset");
    }

    @Test
    void replicasOfTheOldHistoryMayContinueOnlyIfTheyAreNotAheadOfThePromotedNode() {
        ReplicationStats stats = new ReplicationStats();
        String old = "a".repeat(40);
        stats.adopt(old, 1_500);
        stats.shiftReplicationId();

        assertTrue(stats.continuesHistory(old, 1_500), "caught up: continue");
        assertTrue(stats.continuesHistory(old, 900), "behind: the missing bytes are in the backlog");
        assertFalse(stats.continuesHistory(old, 1_501),
                "ahead of the promoted node: it has writes the new primary never saw, so it must resync fully");
        assertTrue(stats.continuesHistory(stats.replid(), 1_501), "the new history itself is always fine");
        assertFalse(stats.continuesHistory("c".repeat(40), 10), "an unrelated history never matches");
    }

    @Test
    void followingAnotherPrimaryForgetsThePromotionRecord() {
        ReplicationStats stats = new ReplicationStats();
        stats.adopt("a".repeat(40), 100);
        stats.shiftReplicationId();
        assertFalse(stats.replid2().isEmpty());

        stats.adopt("b".repeat(40), 400); // this node lost the role and now follows somebody else

        assertEquals("", stats.replid2());
        assertEquals(-1, stats.secondReplidOffset());
        assertEquals("b".repeat(40), stats.replid());
    }

    @Test
    void adoptingTheSameHistoryKeepsTheRecord() {
        ReplicationStats stats = new ReplicationStats();
        stats.adopt("a".repeat(40), 100);
        stats.shiftReplicationId();
        String current = stats.replid();

        stats.adopt(current, 120); // a reconnect to itself, same history

        assertEquals("a".repeat(40), stats.replid2());
    }
}
