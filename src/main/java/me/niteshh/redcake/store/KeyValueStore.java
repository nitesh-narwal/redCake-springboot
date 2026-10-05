package me.niteshh.redcake.store;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Storage abstraction used by all commands.
 *
 * <p>Contract for every implementation:
 * <ul>
 *   <li>Each method is atomic with respect to the key(s) it touches.</li>
 *   <li>Expired keys behave as if they do not exist (lazy expiry on access).</li>
 *   <li>Expiry times are <em>absolute</em> epoch milliseconds so the same value
 *       can be replicated and logged without clock-relative drift.</li>
 *   <li>String operations on a key holding another type throw
 *       {@link WrongTypeException} and leave the key unchanged.</li>
 * </ul>
 */
public interface KeyValueStore {

    // ------------------------------------------------------------ strings

    /** Sets {@code key} to {@code value} with no expiry, replacing any value and TTL. */
    default void set(String key, String value) {
        set(key, value, SetOptions.plain());
    }

    /** Sets {@code key} to {@code value}, expiring at {@code expiresAt} (epoch ms). */
    default void set(String key, String value, long expiresAt) {
        set(key, value, SetOptions.withExpiry(expiresAt));
    }

    /**
     * Full-featured SET (NX/XX/GET/KEEPTTL/expiry). Overwrites a key of any
     * type, except that the {@code GET} option requires the old value to be a string.
     *
     * @return whether the write happened and the previous value
     */
    SetResult set(String key, String value, SetOptions options);

    /** @return the string value, or {@code null} if absent/expired */
    String get(String key);

    /** Atomically removes and returns the string value, or {@code null}. */
    String getAndDelete(String key);

    /**
     * Atomically adds {@code amount} to the integer stored at {@code key}
     * (missing key counts as 0), preserving its TTL.
     *
     * @throws InvalidIntegerException if the value is not an integer or would overflow
     */
    long increment(String key, long amount);

    /**
     * Appends {@code suffix} to the value (creating it if missing), preserving the TTL.
     *
     * @return the new value length in bytes
     */
    long append(String key, String suffix);

    // ------------------------------------------------------ typed values

    /**
     * Reads a collection under the key's lock so concurrent writers cannot
     * corrupt the view. {@code reader} must be quick and must not modify the collection.
     *
     * @param reader receives the collection, or {@code null} if the key is absent
     * @return whatever {@code reader} returns
     * @throws WrongTypeException if the key holds another type
     */
    <T> T read(String key, ValueType type, Function<Object, T> reader);

    /**
     * Atomically modifies (and if needed creates) a collection. The version
     * is bumped, the TTL kept, and the key removed if the collection ends up empty.
     *
     * @param factory creates the empty collection when the key is absent;
     *                {@code null} means "do nothing if absent" (mutator not called, returns {@code null})
     * @param mutator validates its input <em>before</em> changing the collection
     * @throws WrongTypeException if the key holds another type
     */
    <T> T mutate(String key, ValueType type, Supplier<Object> factory, Function<Object, T> mutator);

    /** @return the type of a live key, or {@code null} */
    ValueType type(String key);

    // ------------------------------------------------------- generic keys

    /** @return {@code true} if a live key (of any type) was removed */
    boolean delete(String key);

    /** @return {@code true} if the key exists (any type) and is not expired */
    boolean exists(String key);

    /** Sets an absolute expiry. Equivalent to {@code expire(key, at, NONE)}. */
    default boolean expire(String key, long expiresAt) {
        return expire(key, expiresAt, ExpireCondition.NONE);
    }

    /**
     * Sets an absolute expiry subject to {@code condition}.
     * A deadline in the past deletes the key (Redis behaviour) and returns {@code true}.
     *
     * @return {@code true} if the key existed and the condition allowed the change
     */
    boolean expire(String key, long expiresAt, ExpireCondition condition);

    /** Removes the TTL of a key. @return {@code true} if a TTL was removed */
    boolean persist(String key);

    /**
     * Remaining time to live in seconds (rounded to nearest, like Redis);
     * -2 if the key does not exist, -1 if it has no TTL.
     */
    long ttl(String key);

    /** Remaining time to live in milliseconds; -2 missing, -1 no TTL. */
    long pttl(String key);

    /**
     * Moves {@code source} to {@code destination}, overwriting it and keeping the TTL.
     *
     * @return {@code false} if {@code source} does not exist
     */
    boolean rename(String source, String destination);

    /**
     * @return the write-stamp of a live key, or -1 if absent. Changes on every
     * modification; {@code WATCH} compares it to detect concurrent writes.
     */
    long versionOf(String key);

    /** @return number of stored keys (may include not-yet-reaped expired keys) */
    long size();

    /** @return number of keys that currently have a TTL */
    long expiringKeys();

    /** @return all live keys matching the glob {@code pattern} (O(n)) */
    List<String> keys(String pattern);

    /** Removes every key. */
    void clear();

    // ----------------------------------------------- memory & eviction

    /** @return estimated bytes held by all keys (see {@link MemoryEstimator}) */
    long usedMemory();

    /** @return some live key (approximately random), or {@code null} if empty */
    String anyKey();

    /** @return the key with the soonest TTL, or {@code null} if no key has a TTL */
    String volatileCandidate();

    // --------------------------------------------- snapshots & callbacks

    /**
     * Visits every live key with a private copy of its value. Callers that
     * need a point-in-time view (replication, AOF rewrite) hold the primary
     * write lock while calling it.
     */
    void forEachSnapshot(Consumer<SnapshotEntry> consumer);

    /** Registers the callback invoked after a key expires (may be {@code null}). */
    void setExpiryListener(ExpiryListener listener);
}
