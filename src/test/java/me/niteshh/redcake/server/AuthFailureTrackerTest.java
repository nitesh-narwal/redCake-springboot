package me.niteshh.redcake.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthFailureTrackerTest {

    @Test
    void locksOutAfterTooManyFailuresPerAddress() {
        AuthFailureTracker tracker = new AuthFailureTracker();
        for (int i = 0; i < AuthFailureTracker.MAX_FAILURES - 1; i++) {
            tracker.recordFailure("10.0.0.1");
        }
        assertFalse(tracker.isLockedOut("10.0.0.1"));

        tracker.recordFailure("10.0.0.1");
        assertTrue(tracker.isLockedOut("10.0.0.1"));
        assertFalse(tracker.isLockedOut("10.0.0.2"), "other addresses are unaffected");
    }

    @Test
    void successfulLoginResetsTheCounter() {
        AuthFailureTracker tracker = new AuthFailureTracker();
        for (int i = 0; i < AuthFailureTracker.MAX_FAILURES - 1; i++) {
            tracker.recordFailure("a");
        }
        tracker.reset("a");
        tracker.recordFailure("a");
        assertFalse(tracker.isLockedOut("a"));
    }
}
