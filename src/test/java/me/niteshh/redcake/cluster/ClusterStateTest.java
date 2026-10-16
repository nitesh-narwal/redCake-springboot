package me.niteshh.redcake.cluster;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** The write fence, with a fake clock so lease expiry is exact and instant to test. */
class ClusterStateTest {

    private static final long MS = 1_000_000L;

    private final AtomicLong clock = new AtomicLong(5_000 * MS);
    private final ClusterState state = new ClusterState(true, 1_000, clock::get);

    private void lead(long leaseStart) {
        state.zkConnected();
        state.leading("n1", "10.0.0.1:6379", 7, leaseStart);
    }

    @Test
    void standaloneNeverFencesAnything() {
        ClusterState standalone = ClusterState.standalone();

        assertTrue(standalone.mayWrite());
        assertTrue(standalone.canServeReplicas());
        assertTrue(standalone.isDataTrusted());
        assertFalse(standalone.isFenced());
        assertEquals("", standalone.primaryHint());
    }

    @Test
    void aClusterNodeCannotWriteUntilItLeads() {
        assertEquals(ClusterState.Mode.JOINING, state.mode());
        assertFalse(state.mayWrite());
        assertTrue(state.writeRefusal().startsWith("CLUSTERDOWN"));
        assertFalse(state.isDataTrusted(), "an AOF replay alone is not a trusted dataset");

        state.electing();
        assertFalse(state.mayWrite());
        assertTrue(state.writeRefusal().contains("no primary is elected"));
    }

    @Test
    void theLeaderWritesWhileItsLeaseIsFresh() {
        lead(clock.get());

        assertTrue(state.mayWrite());
        assertTrue(state.canServeReplicas());
        assertTrue(state.isDataTrusted());
        assertFalse(state.isFenced());
        assertEquals(1_000, state.leaseRemainingMillis());
    }

    @Test
    void theLeaseRunsOutExactlyWhenItShould() {
        lead(clock.get());

        clock.addAndGet(999 * MS);
        assertTrue(state.mayWrite(), "1 ms of lease left");
        clock.addAndGet(1 * MS);
        assertFalse(state.mayWrite(), "lease over: a paused or cut-off primary stops writing by itself");
        assertTrue(state.isFenced());
        assertTrue(state.writeRefusal().contains("lease"));
        assertFalse(state.canServeReplicas(), "a fenced primary must not hand its data to new replicas");
    }

    @Test
    void renewingExtendsTheLeaseFromTheInstantLeadershipWasProven() {
        lead(clock.get());
        clock.addAndGet(800 * MS);

        long provenAt = clock.get() - 300 * MS; // the ZooKeeper read started 300 ms ago
        state.renewLease(provenAt);

        assertEquals(700, state.leaseRemainingMillis(), "lease counts from when the read started, not when it ended");
    }

    @Test
    void suspendingTheConnectionFencesImmediatelyEvenWithLeaseLeft() {
        lead(clock.get());
        assertTrue(state.mayWrite());

        state.zkSuspended();

        assertFalse(state.mayWrite());
        assertTrue(state.writeRefusal().contains("ZooKeeper connection"));
    }

    @Test
    void afterAReconnectTheLeaderMustProveItselfAgainBeforeWriting() {
        lead(clock.get());
        state.zkSuspended();

        state.zkConnected();
        assertFalse(state.mayWrite(), "the suspension voided the lease; the lease thread has to renew it");

        state.renewLease(clock.get());
        assertTrue(state.mayWrite());
    }

    @Test
    void aLostSessionFences() {
        lead(clock.get());
        state.zkLost();

        assertFalse(state.mayWrite());
        assertEquals(ClusterState.ZkState.LOST, state.zkState());
    }

    @Test
    void pausingWritesForAHandOverFencesAndResumingReopens() {
        lead(clock.get());

        state.pauseWrites();
        assertFalse(state.mayWrite());
        assertTrue(state.writeRefusal().contains("handing over"));

        state.resumeWrites();
        assertTrue(state.mayWrite());
    }

    @Test
    void aFollowerAnswersReadonlyAndNamesThePrimary() {
        state.zkConnected();
        state.following("n2", "10.0.0.2", 6380, 8);

        assertFalse(state.mayWrite());
        assertEquals("10.0.0.2:6380", state.leaderAddress());
        assertEquals("READONLY replica does not accept writes (the primary is 10.0.0.2:6380)", state.writeRefusal());
        assertEquals(8, state.epoch());
    }

    @Test
    void demotingClearsTheLeaderAndKeepsCounting() {
        lead(clock.get());
        state.electing();
        state.demoted();

        assertFalse(state.mayWrite());
        assertEquals("", state.leaderAddress());
        assertEquals(1, state.promotions());
        assertEquals(1, state.demotions());
    }

    @Test
    void anExpiredLeaseStaysExpiredEvenIfTheNanoClockHasANegativeOrigin() {
        // System.nanoTime() may be negative; "expired" must not be encoded as the number 0.
        clock.set(-10_000 * MS);
        lead(clock.get());
        state.zkSuspended();
        state.zkConnected();

        assertFalse(state.mayWrite());
    }

    @Test
    void theLeaseFollowsTheSessionTimeoutZooKeeperActuallyGranted() {
        lead(clock.get());

        state.leaseMillis(200);
        state.renewLease(clock.get());

        assertEquals(200, state.leaseRemainingMillis());
    }
}
