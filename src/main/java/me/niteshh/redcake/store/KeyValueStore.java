package me.niteshh.redcake.store;

public interface KeyValueStore {
    void set(String key, String value);

    void set(String key, String value, long expiresAt);

    String get(String key);

    boolean delete(String key);

    boolean exists(String key);

    boolean expire(String key, long expiresAt);

    /**
     * Returns the remaining time to live of a key in seconds.
     * If the key does not exist or has expired, it returns -2.
     * If the key exists but has no associated expiration time, it returns -1.
     *
     * @param key The key to check the remaining time to live for.
     * @return The remaining time to live in seconds, or -2 if the key does not exist or has expired, or -1 if the key exists but has no expiration time.
     */
    long ttl(String key);

    /**
     * Returns the remaining time to live of a key in milliseconds.
     * If the key does not exist or has expired, it returns -2.
     * If the key exists but has no associated expiration time, it returns -1.
     *
     * @param key The key to check the remaining time to live for.
     * @return The remaining time to live in milliseconds, or -2 if the key does not exist or has expired, or -1 if the key exists but has no expiration time.
     */
    long pttl(String key);

    /**
     * Increments the integer value of a key by the given amount.
     * If the key does not exist, it is set to 0 before performing the operation.
     * If the key contains a value of the wrong type or contains a string that cannot be represented as an integer, an exception is thrown.
     *
     * @param key The key to increment.
     * @param amount The amount to increment by.
     * @return The new value after incrementing.
     * @throws InvalidIntegerException if the current value cannot be represented as an integer.
     */
    long increment(String key, long amount);
}
