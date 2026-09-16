package me.niteshh.redcake.store;

public interface KeyValueStore {
    void set(String key, String value);

    void set(String key, String value, long expiresAt);

    String get(String key);

    boolean delete(String key);

    boolean exists(String key);

    boolean expire(String key, long expiresAt);

    long ttl(String key);

    long pttl(String key);
}
