package me.niteshh.redcake.store;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * Who is waiting for something to happen to a key: the connections blocked in {@code XREAD BLOCK} and
 * {@code XREADGROUP BLOCK}. A blocked connection is a virtual thread parked here, so it costs a few hundred bytes and
 * holds no lock, no worker and - importantly - not the primary write lock (ADR 0001: never wait while holding it).
 *
 * <h3>How a wake-up cannot be lost</h3>
 * Every key has a version that {@link #wake} increases. A waiter {@linkplain #register registers}, <em>then</em> notes
 * the versions, <em>then</em> tries the command; if the command found nothing it parks until a version differs from
 * the one it noted. A change that happens after the waiter noted the versions therefore always shows; a change that
 * happened before is seen by the command itself. {@code wake} is called after the data has changed.
 *
 * <p>Waking is cheap when nobody waits (one atomic read), which is the case for almost every write.
 */
public final class KeyWaiters {

    private final ConcurrentHashMap<String, Slot> slots = new ConcurrentHashMap<>();
    private final AtomicInteger waiting = new AtomicInteger();

    /** The waiters of one key. */
    private static final class Slot {
        final AtomicLong version = new AtomicLong();
        /** In the order they started waiting. */
        final ConcurrentLinkedQueue<Registration> registered = new ConcurrentLinkedQueue<>();
        /** Guarded by the map's per-key lock (all changes happen inside {@code compute}). */
        int references;
    }

    /** @return the number of connections waiting right now ({@code blocked_clients}) */
    public int waiting() {
        return waiting.get();
    }

    /** Something happened to {@code key} that a waiting reader should look at: new entries, or the key is gone. */
    public void wake(String key) {
        if (waiting.get() == 0) {
            return;
        }
        Slot slot = slots.get(key);
        if (slot != null) {
            slot.version.incrementAndGet();
            for (Registration registration : slot.registered) {
                LockSupport.unpark(registration.thread);
            }
        }
    }

    /** Wakes every waiter ({@code FLUSHALL}). */
    public void wakeAll() {
        if (waiting.get() == 0) {
            return;
        }
        for (String key : slots.keySet()) {
            wake(key);
        }
    }

    /**
     * Starts waiting for changes of these keys on behalf of the calling thread. Close the result when done.
     */
    public Registration register(Collection<String> keys) {
        waiting.incrementAndGet();
        Registration registration = new Registration(new LinkedHashSet<>(keys).toArray(new String[0]));
        for (int i = 0; i < registration.keys.length; i++) {
            registration.slots[i] = slots.compute(registration.keys[i], (key, slot) -> {
                Slot found = slot == null ? new Slot() : slot;
                found.references++;
                found.registered.add(registration);
                return found;
            });
        }
        return registration;
    }

    /** One waiting connection. */
    public final class Registration implements AutoCloseable {
        private final Thread thread = Thread.currentThread();
        private final String[] keys;
        private final Slot[] slots;
        private boolean closed;

        private Registration(String[] keys) {
            this.keys = keys;
            this.slots = new Slot[keys.length];
        }

        /** The current versions; note them <em>before</em> trying the command, hand them to {@link #await}. */
        public long[] versions() {
            long[] versions = new long[slots.length];
            for (int i = 0; i < versions.length; i++) {
                versions[i] = slots[i].version.get();
            }
            return versions;
        }

        private boolean changedSince(long[] seen) {
            for (int i = 0; i < seen.length; i++) {
                if (slots[i].version.get() != seen[i]) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Parks until one of the keys was woken after {@code seen} was taken.
         *
         * @param deadlineNanos {@link System#nanoTime()} to give up at, {@link Long#MAX_VALUE} for never
         * @param tickNanos     how often to look at {@code alive}
         * @param alive         asked every tick; {@code false} (the client hung up, the server stops) ends the wait
         * @return {@code true} if a key changed - try again; {@code false} on timeout, interrupt or {@code !alive}
         */
        public boolean await(long[] seen, long deadlineNanos, long tickNanos, BooleanSupplier alive) {
            while (true) {
                if (changedSince(seen)) {
                    return true;
                }
                long nap = tickNanos;
                if (deadlineNanos != Long.MAX_VALUE) {
                    long remaining = deadlineNanos - System.nanoTime();
                    if (remaining <= 0) {
                        return false;
                    }
                    nap = Math.min(nap, remaining);
                }
                LockSupport.parkNanos(this, nap);
                if (Thread.currentThread().isInterrupted()) {
                    return false;
                }
                if (changedSince(seen)) {
                    return true;
                }
                if (!alive.getAsBoolean()) {
                    return false;
                }
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            for (int i = 0; i < keys.length; i++) {
                KeyWaiters.this.slots.compute(keys[i], (key, slot) -> {
                    slot.registered.remove(this);
                    return --slot.references == 0 ? null : slot;
                });
            }
            waiting.decrementAndGet();
        }
    }
}
