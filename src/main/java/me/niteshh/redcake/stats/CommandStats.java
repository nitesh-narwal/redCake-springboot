package me.niteshh.redcake.stats;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Per-command call counts and execution time, plus a latency histogram over
 * all commands. Feeds {@code INFO commandstats} and the Prometheus endpoint.
 *
 * <p>Only the command <em>execution</em> is timed (not network read/write),
 * so the numbers reflect RedCake itself rather than the client's network.
 * {@link LongAdder}s keep recording cheap under many concurrent connections.
 */
@Component
public class CommandStats {

    /** Upper bounds of the histogram buckets in microseconds; the last bucket is "+Inf". */
    public static final long[] BUCKET_BOUNDS_MICROS = {100, 500, 1_000, 5_000, 10_000, 50_000, 100_000};

    /** Counters of one command name. */
    public static final class Counter {
        private final LongAdder calls = new LongAdder();
        private final LongAdder micros = new LongAdder();
        private final LongAdder failed = new LongAdder();

        public long calls() {
            return calls.sum();
        }

        public long micros() {
            return micros.sum();
        }

        public long failed() {
            return failed.sum();
        }
    }

    private final ConcurrentHashMap<String, Counter> perCommand = new ConcurrentHashMap<>();
    private final LongAdder[] buckets = new LongAdder[BUCKET_BOUNDS_MICROS.length + 1];
    private final LongAdder totalMicros = new LongAdder();

    public CommandStats() {
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = new LongAdder();
        }
    }

    /**
     * Records one execution.
     *
     * @param name   upper-case command name
     * @param micros execution time
     * @param failed whether the reply was an error
     */
    public void record(String name, long micros, boolean failed) {
        Counter counter = perCommand.computeIfAbsent(name, n -> new Counter());
        counter.calls.increment();
        counter.micros.add(micros);
        if (failed) {
            counter.failed.increment();
        }
        totalMicros.add(micros);

        int bucket = 0;
        while (bucket < BUCKET_BOUNDS_MICROS.length && micros > BUCKET_BOUNDS_MICROS[bucket]) {
            bucket++;
        }
        buckets[bucket].increment();
    }

    /** @return a sorted copy of the per-command counters */
    public Map<String, Counter> snapshot() {
        return new TreeMap<>(perCommand);
    }

    /** @return non-cumulative count per histogram bucket (last = above the largest bound) */
    public long[] bucketCounts() {
        long[] counts = new long[buckets.length];
        for (int i = 0; i < buckets.length; i++) {
            counts[i] = buckets[i].sum();
        }
        return counts;
    }

    /** @return total execution time of all commands in microseconds */
    public long totalMicros() {
        return totalMicros.sum();
    }
}
