package me.niteshh.redcake.store;

import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryKeyValueStore implements KeyValueStore {

    private final ConcurrentHashMap<String, ValueEntry> data = new ConcurrentHashMap<>();

    @Override
    public void set(String key, String value) {
        data.put(key, new ValueEntry(value, null));
    }

    @Override
    public void set(String key, String value, long expiresAt) {
        data.put(key, new ValueEntry(value, expiresAt));
    }

    @Override
    public String get(String key) {

        ValueEntry entry = data.get(key);

        if (entry == null) {
            return null;
        }

        if (entry.isExpired()) {
            data.remove(key, entry);
            return null;
        }
        return entry.value();
    }

    @Override
    public boolean delete(String key) {

        ValueEntry entry = data.get(key);
        if (entry == null) {
            return false;
        }

        if (entry.isExpired()) {
            data.remove(key, entry);
            return false;
        }

        return data.remove(key, entry);
    }

    @Override
    public boolean exists(String key) {

        ValueEntry entry = data.get(key);

        if (entry == null) {
            return false;
        }

        if (entry.isExpired()) {
            data.remove(key, entry);
            return false;
        }
        return true;
    }

    @Override
    public boolean expire(String key, long expiresAt) {

        ValueEntry entry = data.get(key);

        if (entry == null) {
            return false;
        }

        if (entry.isExpired()) {
            data.remove(key, entry);
            return false;
        }

        ValueEntry updatedEntry = new ValueEntry(entry.value(), expiresAt);
        return data.replace(key, entry, updatedEntry
        );
    }

    @Override
    public long ttl(String key) {

        ValueEntry entry = data.get(key);

        if (entry == null) {
            return -2;
        }

        if (entry.isExpired()) {
            data.remove(key, entry);
            return -2;
        }

        if (entry.expiresAt() == null) {
            return -1;
        }

        long remaining = entry.expiresAt() - System.currentTimeMillis();

        if (remaining <= 0) {
            data.remove(key, entry);
            return -2;
        }

        return (remaining + 999) / 1000;
    }

    @Override
    public long pttl(String key) {
        ValueEntry entry = data.get(key);

        if (entry == null) {
            return -2;
        }

        if (entry.isExpired()) {
            data.remove(key, entry);
            return -2;
        }

        if (entry.expiresAt() == null) {
            return -1;
        }

        long remaining = entry.expiresAt() - System.currentTimeMillis();

        if (remaining <= 0) {
            data.remove(key, entry);
            return -2;
        }
        return remaining;
    }
}
