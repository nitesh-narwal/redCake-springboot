package me.niteshh.redcake.store;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
public class InMemoryKeyValueStore implements KeyValueStore {

    private final ConcurrentHashMap<String, ValueEntry> data = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> keyLocks =
            new ConcurrentHashMap<>();

    private final AtomicLong versionGenerator = new AtomicLong();

    private final ExpirationManager expirationManager;

    @PostConstruct
    public void initializeExpirationHandler() {  // Initialize the expiration handler after the bean is constructed
        expirationManager.setExpirationHandler(
                this::expireIfVersionMatches
        );
    }

    /**
     * Sets the value for the given key without an expiration time.
     *
     * @param key The key to set the value for.
     * @param value The value to associate with the key.
     */
    @Override
    public void set(String key, String value) {
        long version = versionGenerator.incrementAndGet();
        data.put(key, new ValueEntry(value, null, version));
    }

    /**
     * Sets the value for the given key with an expiration time.
     *
     * @param key The key to set the value for.
     * @param value The value to associate with the key.
     * @param expiresAt The time at which the key should expire.
     */
    @Override
    public void set(String key, String value, long expiresAt) {
        long version = versionGenerator.incrementAndGet();
        data.put(key, new ValueEntry(value, expiresAt, version));
        expirationManager.schedule(key, expiresAt, version);
    }

    /**
     * Retrieves the value associated with the given key.
     * If the key does not exist or has expired, it returns null.
     *
     * @param key The key to retrieve the value for.
     * @return The value associated with the key, or null if the key does not exist or has expired.
     */
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

    /**
     * Deletes the key-value pair associated with the given key.
     * If the key does not exist or has expired, it returns false.
     *
     * @param key The key to delete.
     * @return true if the key was successfully deleted, false otherwise.
     */
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

    /**
     * Checks if a key exists in the store.
     * If the key does not exist or has expired, it returns false.
     *
     * @param key The key to check.
     * @return true if the key exists and is not expired, false otherwise.
     */
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

    /**
     * Sets an expiration time for the given key.
     * If the key does not exist or has expired, it returns false.
     *
     * @param key The key to set the expiration time for.
     * @param expiresAt The time at which the key should expire.
     * @return true if the expiration time was successfully set, false otherwise.
     */
    @Override
    public boolean expire(String key, long expiresAt) {
        while (true) {
            ValueEntry current = data.get(key);

            if (current == null) {
                return false;
            }

            if (current.isExpired()) {
                data.remove(key, current);
                return false;
            }

            long newVersion = versionGenerator.incrementAndGet(); // Generate a new version for the updated entry

            ValueEntry updated = new ValueEntry(current.value(), expiresAt, newVersion); // Create a new ValueEntry with the updated expiration time and a new version

            boolean replaced = data.replace(key, current, updated);

            if (!replaced) {
                continue;
            }

            expirationManager.schedule(key, expiresAt, newVersion); // Schedule the expiration for the updated entry
            return true;
        }
    }

    /**
     * Returns the remaining time to live for the given key.
     * If the key does not exist or has expired, it returns -2.
     * If the key exists but has no expiration time, it returns -1.
     *
     * @param key The key to get the TTL for.
     * @return The remaining time to live in seconds, or -2 if the key does not exist or has expired, or -1 if the key exists but has no expiration time.
     */
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
        /*
         * Redis-style TTL returns remaining seconds,
         * rounded down/up according to our chosen
         * implementation.
         *
         * We use ceiling here so a key with 1.2 seconds
         * remaining reports 2 seconds.
         */
        return (remaining + 999) / 1000;
    }

    /**
     * Returns the remaining time to live for the given key in milliseconds.
     * If the key does not exist or has expired, it returns -2.
     * If the key exists but has no expiration time, it returns -1.
     *
     * @param key The key to get the TTL for.
     * @return The remaining time to live in milliseconds, or -2 if the key does not exist or has expired, or -1 if the key exists but has no expiration time.
     */
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

    /*
     * Called by ExpirationManager.
     *
     * IMPORTANT:
     * We only delete the value if the expiration event
     * still belongs to the current version of the key.
     */
    public void expireIfVersionMatches(ExpirationEntry expirationEntry) {

        ValueEntry current = data.get(expirationEntry.key());

        if (current == null) {
            return;
        }

        /*
         * Old expiration event.
         * Example:
         * SET key A EX 10
         * SET key B EX 30
         *
         * The 10-second event is now stale.
         */
        if (current.version() != expirationEntry.version()) {
            return;
        }

        /*
         * Extra protection.
         */
        if (current.expiresAt() == null) {
            return;
        }

        if (current.expiresAt() != expirationEntry.expiresAt()) {
            return;
        }

        /*
         * Conditional removal prevents us from deleting
         * a newer value that may have been inserted by
         * another thread.
         */
        data.remove(expirationEntry.key(), current);
    }

    @Override
    public long increment(String key, long amount) {
        Object keyLock = keyLocks.computeIfAbsent(
                key,
                ignored -> new Object()
        );

        synchronized (keyLock) {
            return incrementLocked(key, amount);
        }
    }

    private long incrementLocked(String key, long amount) {

        ValueEntry current = data.get(key);

        /*
         * Redis-style behavior:
         * if the key doesn't exist, treat it as 0.
         */
        if (current == null) {
            long newValue = amount;
            long version = versionGenerator.incrementAndGet();

            ValueEntry newEntry = new ValueEntry(String.valueOf(newValue), null, version);
            data.put(key, newEntry);
            return newValue;
        }

        /*
         * Lazy expiration.
         */
        if (current.isExpired()) {
            data.remove(key, current);
            long newValue = amount;
            long version = versionGenerator.incrementAndGet();

            ValueEntry newEntry = new ValueEntry(String.valueOf(newValue), null, version);
            data.put(key, newEntry);
            return newValue;
        }

        long currentValue;

        try {
            currentValue = Long.parseLong(current.value());
        } catch (NumberFormatException e) {
            throw new InvalidIntegerException("value is not an integer or out of range");
        }

        /*
         * Check for long overflow before addition.
         */
        if ((amount > 0 && currentValue > Long.MAX_VALUE - amount) || (amount < 0 && currentValue < Long.MIN_VALUE - amount)) {
            throw new InvalidIntegerException("increment or decrement would overflow");
        }

        long newValue = currentValue + amount;
        long newVersion = versionGenerator.incrementAndGet();

        /*
         * IMPORTANT:
         *
         * Preserve the existing expiration.
         */
        ValueEntry updated = new ValueEntry(String.valueOf(newValue), current.expiresAt(), newVersion);
        data.put(key, updated);

        /*
         * If the old value had an expiration,
         * schedule a new expiration event using
         * the NEW version.
         */
        if (updated.expiresAt() != null) {
            expirationManager.schedule(key, updated.expiresAt(), newVersion);
        }
        return newValue;
    }

    @Override
    public void forEachSnapshot(Consumer<SnapshotEntry> consumer) {
        long now = System.currentTimeMillis();

        data.forEach((key, entry) -> {
            Long expiresAt = entry.expiresAt();
            if (expiresAt != null && expiresAt <= now) {
                data.remove(key, entry);
                return;
            }

            consumer.accept(
                    new SnapshotEntry(
                            key,
                            entry.value(),
                            expiresAt
                    )
            );
        });
    }
}
