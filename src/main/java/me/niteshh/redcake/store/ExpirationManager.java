package me.niteshh.redcake.store;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Active expiration: a background thread that deletes keys when their TTL
 * elapses, even if nobody reads them (lazy expiry alone would leak memory for
 * keys that are written once and never touched again).
 *
 * <p><b>One entry per key.</b> The schedule is a {@link TreeSet} ordered by
 * deadline plus a {@code key -> entry} index. Re-scheduling a key replaces its
 * previous entry, and {@link #cancel} removes it. The old implementation used
 * a plain priority queue and added a new entry on every {@code SET ... EX},
 * {@code EXPIRE} and {@code INCR}, so stale entries piled up until their
 * deadline - memory grew with the number of <em>writes</em>, now it only grows
 * with the number of keys that currently have a TTL.
 *
 * <p>Thread-safety: all structure access happens under {@link #lock}. The
 * worker releases the lock before invoking the handler so a slow handler never
 * blocks writers calling {@link #schedule}.
 */
@Slf4j
@Component
public class ExpirationManager {

    /** Earliest deadline first; key/version break ties so entries stay distinct. */
    private static final Comparator<ExpirationEntry> ORDER =
            Comparator.comparingLong(ExpirationEntry::expiresAt)
                    .thenComparing(ExpirationEntry::key)
                    .thenComparingLong(ExpirationEntry::version);

    private final TreeSet<ExpirationEntry> queue = new TreeSet<>(ORDER);
    private final Map<String, ExpirationEntry> byKey = new HashMap<>();
    private final Object lock = new Object();

    /** Cleared by {@link #stop()}; volatile so the worker sees it promptly. */
    private volatile boolean running = true;

    private Thread workerThread;

    /** Invoked (outside the lock) for every due entry; set by the store. */
    @Setter
    private volatile Consumer<ExpirationEntry> expirationHandler;

    /**
     * Schedules (or re-schedules) expiry of {@code key}. Any earlier schedule
     * for the same key is discarded.
     */
    public void schedule(String key, long expiresAt, long version) {
        ExpirationEntry entry = new ExpirationEntry(key, expiresAt, version);
        synchronized (lock) {
            ExpirationEntry previous = byKey.put(key, entry);
            if (previous != null) {
                queue.remove(previous);
            }
            queue.add(entry);
            // The new entry may be earlier than the one the worker sleeps on.
            lock.notifyAll();
        }
    }

    /** Forgets the schedule of {@code key} (key deleted, persisted or overwritten without TTL). */
    public void cancel(String key) {
        synchronized (lock) {
            ExpirationEntry previous = byKey.remove(key);
            if (previous != null) {
                queue.remove(previous);
            }
        }
    }

    /** Drops every schedule (used by FLUSHALL and replica full-resync). */
    public void clear() {
        synchronized (lock) {
            queue.clear();
            byKey.clear();
        }
    }

    /**
     * @return the key whose TTL ends first, or {@code null} if no key has a
     * TTL. Used by the {@code volatile-ttl} eviction policy.
     */
    public String earliestKey() {
        synchronized (lock) {
            return queue.isEmpty() ? null : queue.first().key();
        }
    }

    /** @return number of keys that currently have a pending expiration */
    public int size() {
        synchronized (lock) {
            return byKey.size();
        }
    }

    @PostConstruct
    public void start() {
        workerThread = new Thread(this::expirationLoop, "RedCake-ExpirationThread");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    /** Worker loop: sleep until the earliest deadline, then hand the entry to the handler. */
    private void expirationLoop() {
        while (running) {
            try {
                ExpirationEntry entry;
                synchronized (lock) {
                    while (queue.isEmpty() && running) {
                        lock.wait();
                    }
                    if (!running) {
                        break;
                    }

                    entry = queue.first();
                    long now = System.currentTimeMillis();
                    if (entry.expiresAt() > now) {
                        lock.wait(entry.expiresAt() - now);
                        continue; // re-evaluate: head may have changed while sleeping
                    }

                    queue.pollFirst();
                    byKey.remove(entry.key(), entry);
                }

                Consumer<ExpirationEntry> handler = expirationHandler;
                if (handler != null) {
                    handler.accept(entry);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("Expiration worker error", e);
            }
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        synchronized (lock) {
            lock.notifyAll();
        }
        if (workerThread != null) {
            workerThread.interrupt();
        }
    }
}
