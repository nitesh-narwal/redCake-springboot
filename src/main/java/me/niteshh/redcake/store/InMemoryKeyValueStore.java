package me.niteshh.redcake.store;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Default in-memory {@link KeyValueStore} backed by a {@link ConcurrentHashMap}.
 *
 * <h3>Concurrency design</h3>
 * Every mutation goes through {@code ConcurrentHashMap.compute*}, which runs
 * the lambda while holding that key's bin lock. SET, DEL, EXPIRE, INCR, HSET,
 * LPUSH ... on the same key are therefore mutually atomic - there is no
 * "read, think, put" window where a concurrent change could be lost.
 * Plain reads ({@code data.get}) are lock-free.
 *
 * <h3>Central bookkeeping</h3>
 * {@link #install} and {@link #remove} are the only places that create or
 * drop an entry. They also (a) keep the {@link ExpirationManager} schedule in
 * step with the data, from <em>inside</em> the compute lambda, so schedule and
 * data can never disagree about which write is current, and (b) maintain the
 * {@link #usedMemory()} estimate used for {@code maxmemory}. Lock order is
 * always bin lock, then the manager's lock; the manager never calls back into
 * the store while holding its own lock.
 *
 * <h3>Expiry notifications</h3>
 * When a key is removed because its TTL elapsed, {@link ExpiryListener} is
 * called <em>after</em> the compute section (never while a bin lock is held).
 */
@Component
@RequiredArgsConstructor
public class InMemoryKeyValueStore implements KeyValueStore {

    private final ConcurrentHashMap<String, ValueEntry> data = new ConcurrentHashMap<>();

    /** Source of unique write stamps (see {@link ValueEntry#version()}). */
    private final AtomicLong versionGenerator = new AtomicLong();

    /** Sum of {@link ValueEntry#bytes()} over all entries. */
    private final AtomicLong memory = new AtomicLong();

    private final ExpirationManager expirationManager;

    private volatile ExpiryListener expiryListener;

    /** Wires the background expiration worker to {@link #expireIfVersionMatches}. */
    @PostConstruct
    public void initializeExpirationHandler() {
        expirationManager.setExpirationHandler(this::expireIfVersionMatches);
    }

    @Override
    public void setExpiryListener(ExpiryListener listener) {
        this.expiryListener = listener;
    }

    // ------------------------------------------------ entry bookkeeping

    /**
     * Creates the entry that replaces {@code old}: fresh version, memory
     * accounting, expiry schedule. Call only from inside a compute lambda.
     */
    private ValueEntry install(String key, ValueEntry old, Object value, Long expiresAt) {
        long version = versionGenerator.incrementAndGet();
        ValueEntry entry = new ValueEntry(value, expiresAt, version, MemoryEstimator.sizeOf(key, value));
        memory.addAndGet(entry.bytes() - (old == null ? 0 : old.bytes()));

        if (expiresAt != null) {
            expirationManager.schedule(key, expiresAt, version);
        } else if (old != null && old.expiresAt() != null) {
            expirationManager.cancel(key);
        }
        return entry;
    }

    /** Accounts for dropping {@code old}; returns {@code null} so a lambda can {@code return remove(...)}. */
    private ValueEntry remove(String key, ValueEntry old) {
        memory.addAndGet(-old.bytes());
        if (old.expiresAt() != null) {
            expirationManager.cancel(key);
        }
        return null;
    }

    private void notifyExpired(String key) {
        ExpiryListener listener = expiryListener;
        if (listener != null) {
            listener.onExpired(key);
        }
    }

    // ---------------------------------------------------------------- SET

    /**
     * Atomic SET. Inside the bin lock: treat an expired entry as absent,
     * evaluate NX/XX, decide the TTL (explicit, KEEPTTL or none), install.
     */
    @Override
    public SetResult set(String key, String value, SetOptions options) {
        long now = System.currentTimeMillis();
        boolean[] applied = {false};
        String[] previous = {null};

        data.compute(key, (k, current) -> {
            ValueEntry live = (current == null || current.isExpiredAt(now)) ? null : current;
            if (options.returnOld() && live != null) {
                previous[0] = live.asString(); // WRONGTYPE for non-strings, key untouched
            }

            boolean blocked =
                    (options.condition() == SetOptions.Condition.ONLY_IF_ABSENT && live != null)
                            || (options.condition() == SetOptions.Condition.ONLY_IF_PRESENT && live == null);
            if (blocked) {
                return (current != null && live == null) ? remove(k, current) : current;
            }

            applied[0] = true;
            Long expiresAt = options.expiresAt();
            if (expiresAt == null && options.keepTtl() && live != null) {
                expiresAt = live.expiresAt();
            }
            if (expiresAt != null && expiresAt <= now) {
                return current == null ? null : remove(k, current); // already expired: no key
            }
            return install(k, current, value, expiresAt);
        });

        return new SetResult(applied[0], previous[0]);
    }

    // -------------------------------------------------------------- reads

    @Override
    public String get(String key) {
        ValueEntry entry = data.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired()) {
            evictIfExpired(key);
            return null;
        }
        return entry.asString();
    }

    @Override
    public boolean exists(String key) {
        ValueEntry entry = data.get(key);
        if (entry == null) {
            return false;
        }
        if (entry.isExpired()) {
            evictIfExpired(key);
            return false;
        }
        return true;
    }

    @Override
    public ValueType type(String key) {
        ValueEntry entry = data.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired()) {
            evictIfExpired(key);
            return null;
        }
        return entry.type();
    }

    @Override
    public long versionOf(String key) {
        ValueEntry entry = data.get(key);
        return entry == null || entry.isExpired() ? -1 : entry.version();
    }

    @Override
    public long pttl(String key) {
        ValueEntry entry = data.get(key);
        if (entry == null) {
            return -2;
        }
        if (entry.expiresAt() == null) {
            return -1;
        }
        long remaining = entry.expiresAt() - System.currentTimeMillis();
        if (remaining <= 0) {
            evictIfExpired(key);
            return -2;
        }
        return remaining;
    }

    /** Redis rounds the millisecond TTL to the nearest second. */
    @Override
    public long ttl(String key) {
        long millis = pttl(key);
        return millis < 0 ? millis : (millis + 500) / 1000;
    }

    // ------------------------------------------------------------- delete

    @Override
    public boolean delete(String key) {
        boolean[] removed = {false};
        data.computeIfPresent(key, (k, current) -> {
            removed[0] = !current.isExpired();
            return remove(k, current);
        });
        return removed[0];
    }

    @Override
    public String getAndDelete(String key) {
        String[] removed = {null};
        data.computeIfPresent(key, (k, current) -> {
            if (!current.isExpired()) {
                removed[0] = current.asString();
            }
            return remove(k, current);
        });
        return removed[0];
    }

    // ------------------------------------------------------------- expiry

    @Override
    public boolean expire(String key, long expiresAt, ExpireCondition condition) {
        long now = System.currentTimeMillis();
        boolean[] changed = {false};

        data.computeIfPresent(key, (k, current) -> {
            if (current.isExpiredAt(now)) {
                return remove(k, current);
            }

            Long existing = current.expiresAt();
            boolean allowed = switch (condition) {
                case NONE -> true;
                case NX -> existing == null;
                case XX -> existing != null;
                case GT -> existing != null && expiresAt > existing;
                case LT -> existing == null || expiresAt < existing;
            };
            if (!allowed) {
                return current;
            }

            changed[0] = true;
            if (expiresAt <= now) {
                return remove(k, current); // deadline already passed: delete (Redis semantics)
            }
            return install(k, current, current.value(), expiresAt);
        });

        return changed[0];
    }

    @Override
    public boolean persist(String key) {
        long now = System.currentTimeMillis();
        boolean[] changed = {false};

        data.computeIfPresent(key, (k, current) -> {
            if (current.isExpiredAt(now)) {
                return remove(k, current);
            }
            if (current.expiresAt() == null) {
                return current;
            }
            changed[0] = true;
            return install(k, current, current.value(), null);
        });

        return changed[0];
    }

    /**
     * Called by the expiration worker. Deletes the key only if it still holds
     * the write-stamp the event was scheduled for, so a stale event can never
     * remove a newer value.
     */
    public void expireIfVersionMatches(ExpirationEntry event) {
        boolean[] removed = {false};
        data.computeIfPresent(event.key(), (k, current) -> {
            if (current.version() != event.version()
                    || current.expiresAt() == null
                    || current.expiresAt() != event.expiresAt()) {
                return current;
            }
            removed[0] = true;
            return remove(k, current);
        });
        if (removed[0]) {
            notifyExpired(event.key());
        }
    }

    /** Lazy expiry: removes {@code key} if (and only if) it is expired right now. */
    private void evictIfExpired(String key) {
        boolean[] removed = {false};
        data.computeIfPresent(key, (k, current) -> {
            if (current.isExpired()) {
                removed[0] = true;
                return remove(k, current);
            }
            return current;
        });
        if (removed[0]) {
            notifyExpired(key);
        }
    }

    // ---------------------------------------------------- read-modify-write

    /**
     * Atomic add. Starts from 0 when the key is missing/expired, rejects
     * non-integers and long overflow, keeps the TTL.
     */
    @Override
    public long increment(String key, long amount) {
        long now = System.currentTimeMillis();
        long[] result = {0};

        data.compute(key, (k, current) -> {
            if (current == null || current.isExpiredAt(now)) {
                result[0] = amount;
                return install(k, current, Long.toString(amount), null);
            }

            long currentValue;
            try {
                currentValue = Long.parseLong(current.asString());
            } catch (NumberFormatException e) {
                throw new InvalidIntegerException("value is not an integer or out of range");
            }

            if ((amount > 0 && currentValue > Long.MAX_VALUE - amount)
                    || (amount < 0 && currentValue < Long.MIN_VALUE - amount)) {
                throw new InvalidIntegerException("increment or decrement would overflow");
            }

            result[0] = currentValue + amount;
            return install(k, current, Long.toString(result[0]), current.expiresAt());
        });

        return result[0];
    }

    /** Values are byte strings (one char = one byte), so length() is the byte length. */
    @Override
    public long append(String key, String suffix) {
        long now = System.currentTimeMillis();
        long[] length = {0};

        data.compute(key, (k, current) -> {
            boolean live = current != null && !current.isExpiredAt(now);
            String joined = (live ? current.asString() : "") + suffix;
            length[0] = joined.length();
            return install(k, current, joined, live ? current.expiresAt() : null);
        });

        return length[0];
    }

    // -------------------------------------------------------- typed values

    @Override
    @SuppressWarnings("unchecked")
    public <T> T read(String key, ValueType type, Function<Object, T> reader) {
        if (data.get(key) == null) {
            return reader.apply(null);
        }
        Object[] result = {null};
        boolean[] called = {false};
        boolean[] expired = {false};
        long now = System.currentTimeMillis();

        data.computeIfPresent(key, (k, current) -> {
            if (current.isExpiredAt(now)) {
                expired[0] = true;
                return remove(k, current);
            }
            if (current.type() != type) {
                throw new WrongTypeException();
            }
            called[0] = true;
            result[0] = reader.apply(current.value());
            return current;
        });

        if (expired[0]) {
            notifyExpired(key);
        }
        return called[0] ? (T) result[0] : reader.apply(null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T mutate(String key, ValueType type, Supplier<Object> factory, Function<Object, T> mutator) {
        long now = System.currentTimeMillis();
        Object[] result = {null};

        data.compute(key, (k, current) -> {
            ValueEntry live = (current == null || current.isExpiredAt(now)) ? null : current;
            if (live != null && live.type() != type) {
                throw new WrongTypeException();
            }

            Object collection;
            Long expiresAt;
            if (live == null) {
                if (factory == null) {
                    return current == null ? null : remove(k, current);
                }
                collection = factory.get();
                expiresAt = null;
            } else {
                collection = live.value();
                expiresAt = live.expiresAt();
            }

            result[0] = mutator.apply(collection);

            if (MemoryEstimator.isEmpty(collection)) {
                // Redis never keeps empty collections.
                return current == null ? null : remove(k, current);
            }
            return install(k, current, collection, expiresAt);
        });

        return (T) result[0];
    }

    // ------------------------------------------------------------- rename

    /**
     * Two-step move (remove source, then write destination). Each step is
     * atomic; a concurrent <em>reader</em> may briefly see neither key. All
     * writers are serialized by the primary write lock, so no writer can
     * interleave.
     */
    @Override
    public boolean rename(String source, String destination) {
        if (source.equals(destination)) {
            return exists(source);
        }

        ValueEntry[] taken = {null};
        data.computeIfPresent(source, (k, current) -> {
            if (!current.isExpired()) {
                taken[0] = current;
            }
            return remove(k, current);
        });
        if (taken[0] == null) {
            return false;
        }

        ValueEntry moved = taken[0];
        data.compute(destination, (k, old) -> install(k, old, moved.value(), moved.expiresAt()));
        return true;
    }

    // ------------------------------------------------------------ keyspace

    @Override
    public long size() {
        return data.size();
    }

    @Override
    public long expiringKeys() {
        return expirationManager.size();
    }

    @Override
    public List<String> keys(String pattern) {
        long now = System.currentTimeMillis();
        boolean matchAll = "*".equals(pattern);
        List<String> matches = new ArrayList<>();

        data.forEach((key, entry) -> {
            if (!entry.isExpiredAt(now) && (matchAll || GlobMatcher.matches(pattern, key))) {
                matches.add(key);
            }
        });
        return matches;
    }

    @Override
    public void clear() {
        data.clear();
        memory.set(0);
        expirationManager.clear();
    }

    @Override
    public long usedMemory() {
        return memory.get();
    }

    /**
     * Not truly random (it returns the first key of the map's iteration
     * order), but iteration order is unrelated to key meaning, which is good
     * enough for the {@code allkeys-random} policy and O(1).
     */
    @Override
    public String anyKey() {
        var iterator = data.keySet().iterator();
        return iterator.hasNext() ? iterator.next() : null;
    }

    @Override
    public String volatileCandidate() {
        return expirationManager.earliestKey();
    }

    // ----------------------------------------------------------- snapshots

    @Override
    public void forEachSnapshot(Consumer<SnapshotEntry> consumer) {
        long now = System.currentTimeMillis();
        for (String key : data.keySet()) {
            // Copy under the key's lock so a typed value cannot change mid-copy.
            SnapshotEntry[] captured = {null};
            data.computeIfPresent(key, (k, entry) -> {
                if (entry.isExpiredAt(now)) {
                    return entry; // reaped lazily/by the worker; just skip it
                }
                captured[0] = new SnapshotEntry(k, copyOf(entry.value()), entry.expiresAt());
                return entry;
            });
            if (captured[0] != null) {
                consumer.accept(captured[0]);
            }
        }
    }

    /** Strings are immutable; collections are copied so the snapshot is stable. */
    private static Object copyOf(Object value) {
        return switch (value) {
            case Map<?, ?> map -> new LinkedHashMap<>(map);
            case List<?> list -> new ArrayList<>(list);
            case Set<?> set -> new LinkedHashSet<>(set);
            case ZSet zset -> zset.copy();
            default -> value;
        };
    }
}
