package me.niteshh.redcake.server;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Brute-force protection for {@code AUTH}: after {@value #MAX_FAILURES}
 * failed attempts from one IP inside {@value #WINDOW_MILLIS} ms, that IP is
 * locked out for {@value #LOCKOUT_MILLIS} ms (a correct key is refused too,
 * otherwise the lockout would not slow an attacker down).
 *
 * <p>Memory is bounded: when the table passes {@value #MAX_TRACKED} addresses
 * stale records are purged first and, if still full, the table is reset.
 */
@Component
public class AuthFailureTracker {

    static final int MAX_FAILURES = 10;
    static final long WINDOW_MILLIS = 60_000;
    static final long LOCKOUT_MILLIS = 60_000;
    private static final int MAX_TRACKED = 10_000;

    /** Mutable record guarded by the map's per-key atomic compute. */
    private static final class Record {
        int failures;
        long windowStart;
        long lockedUntil;
    }

    private final ConcurrentHashMap<String, Record> records = new ConcurrentHashMap<>();

    /** @return {@code true} if {@code address} is currently locked out */
    public boolean isLockedOut(String address) {
        Record record = records.get(address);
        return record != null && record.lockedUntil > System.currentTimeMillis();
    }

    /** Records one failed attempt and starts a lockout when the threshold is reached. */
    public void recordFailure(String address) {
        long now = System.currentTimeMillis();
        if (records.size() >= MAX_TRACKED) {
            records.entrySet().removeIf(e ->
                    e.getValue().lockedUntil < now && now - e.getValue().windowStart > WINDOW_MILLIS);
            if (records.size() >= MAX_TRACKED) {
                records.clear();
            }
        }

        records.compute(address, (key, existing) -> {
            Record record = existing == null ? new Record() : existing;
            if (now - record.windowStart > WINDOW_MILLIS) {
                record.windowStart = now;
                record.failures = 0;
            }
            record.failures++;
            if (record.failures >= MAX_FAILURES) {
                record.lockedUntil = now + LOCKOUT_MILLIS;
                record.failures = 0;
            }
            return record;
        });
    }

    /** Forgets the failures of {@code address} after a successful login. */
    public void reset(String address) {
        records.remove(address);
    }
}
