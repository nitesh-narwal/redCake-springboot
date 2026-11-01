package me.niteshh.redcake.stream;

import java.util.Collection;
import java.util.Collections;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * A consumer group of a stream: the position up to which entries were handed out ({@link #lastId}), the entries
 * delivered but not acknowledged ({@link #pending}), and the consumers. Groups and consumers are kept sorted by
 * name (byte order) - the order {@code XINFO} lists them in - and the pending lists by id. Changed only by
 * {@link Stream}.
 */
public final class ConsumerGroup {

    final String name;
    StreamId lastId;
    /** How many entries the group has read in the stream's whole history, or -1 when that is not known. */
    long entriesRead;
    final TreeMap<StreamId, Pending> pel = new TreeMap<>();
    final TreeMap<String, Consumer> consumers = new TreeMap<>();

    ConsumerGroup(String name, StreamId lastId, long entriesRead) {
        this.name = name;
        this.lastId = lastId;
        this.entriesRead = entriesRead;
    }

    public String name() {
        return name;
    }

    /** The id of the last entry delivered to the group ({@code >} reads everything after it). */
    public StreamId lastId() {
        return lastId;
    }

    /** The group's read counter, or -1 when unknown. */
    public long entriesRead() {
        return entriesRead;
    }

    public int pendingCount() {
        return pel.size();
    }

    /** The group's pending entries by id (read only). */
    public NavigableMap<StreamId, Pending> pending() {
        return Collections.unmodifiableNavigableMap(pel);
    }

    public Consumer consumer(String consumerName) {
        return consumers.get(consumerName);
    }

    /** The consumers by name (read only). */
    public Collection<Consumer> consumers() {
        return Collections.unmodifiableCollection(consumers.values());
    }
}
