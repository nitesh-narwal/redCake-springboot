package me.niteshh.redcake.stream;

import java.util.Collections;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * A named reader of a consumer group and the entries delivered to it that it has not acknowledged. Changed only by
 * {@link Stream}.
 */
public final class Consumer {

    final String name;
    /** Last time the consumer tried to read or claim anything (epoch ms). */
    long seenTime;
    /** Last time it actually got something (epoch ms), -1 if never. */
    long activeTime = -1;
    final TreeMap<StreamId, Pending> pel = new TreeMap<>();

    Consumer(String name, long now) {
        this.name = name;
        this.seenTime = now;
    }

    public String name() {
        return name;
    }

    public long seenTime() {
        return seenTime;
    }

    public long activeTime() {
        return activeTime;
    }

    public int pendingCount() {
        return pel.size();
    }

    /** The consumer's pending entries by id (read only). */
    public NavigableMap<StreamId, Pending> pending() {
        return Collections.unmodifiableNavigableMap(pel);
    }
}
