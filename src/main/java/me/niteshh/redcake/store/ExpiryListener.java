package me.niteshh.redcake.store;

/**
 * Notified after the store removes a key because its TTL elapsed (by the
 * background worker or lazily on access). A primary uses it to turn every
 * expiry into an explicit {@code DEL} in the replication stream and AOF, so
 * replicas and replays never depend on their own clocks to drop a key.
 *
 * <p>Called <em>outside</em> any store lock, so implementations may take
 * other locks (the write lock) without deadlock risk.
 */
@FunctionalInterface
public interface ExpiryListener {
    void onExpired(String key);
}
