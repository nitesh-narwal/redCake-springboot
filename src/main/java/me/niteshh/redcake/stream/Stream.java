package me.niteshh.redcake.stream;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * A stream: an append-only log of entries ({@link EntryLog}) with consumer groups on top - the value behind
 * {@code XADD}, {@code XREADGROUP}, {@code XACK} and the rest.
 *
 * <p>The rules are those of Redis 7.x, ported from its {@code t_stream.c} rather than guessed, down to the
 * counters that make {@code XINFO}'s {@code lag} come out the same ({@link #entriesAdded}, {@link #maxDeletedId},
 * the group's {@code entries-read}). Where RedCake differs on purpose it says so: trimming with {@code ~} is exact.
 *
 * <p>This class decides, the commands only parse and format. It never reads the clock - the caller passes
 * {@code now} - and it checks before it changes: an operation that throws {@link StreamException} left everything
 * as it was. Not thread-safe: the store's per-key lock protects it, as for every other value type.
 *
 * <h3>What each state change looks like to a replica</h3>
 * The group operations return exactly what they did (who was given which entry, at what time, for the how-manieth
 * time), so that the command can propagate the <em>result</em> - {@code XCLAIM ... FORCE JUSTID}, {@code XGROUP SETID} -
 * and primary, replicas and the AOF cannot disagree about the clock or about which entries a {@code >} read found.
 */
public final class Stream {

    private static final long BASE_BYTES = 192;
    private static final long GROUP_BYTES = 200;
    private static final long CONSUMER_BYTES = 200;
    /** One pending entry: the {@link Pending}, its slot in the group's list and in the consumer's, the id. */
    private static final long PENDING_BYTES = 160;
    private static final long NAME_BYTES = 40;

    /** The estimated "attempts per claimed entry" of {@code XAUTOCLAIM}'s scan, as in Redis. */
    private static final long AUTOCLAIM_ATTEMPTS_FACTOR = 10;

    private final EntryLog log;
    /** The largest id ever used (an entry may have been deleted since). */
    private StreamId lastId = StreamId.ZERO;
    /** The id of the oldest entry, {@code 0-0} while there is none. */
    private StreamId firstId = StreamId.ZERO;
    /** The largest id that {@code XDEL} removed; tells {@link #lag} where a counter can no longer be trusted. */
    private StreamId maxDeletedId = StreamId.ZERO;
    /** Entries ever added, deleted or not. */
    private long entriesAdded;
    private final TreeMap<String, ConsumerGroup> groups = new TreeMap<>();
    private long groupBytes;

    public Stream() {
        this(new EntryLog());
    }

    private Stream(EntryLog log) {
        this.log = log;
    }

    // ------------------------------------------------------------ the counters

    /** The number of entries. */
    public long length() {
        return log.size();
    }

    /** The last id that was handed out; the next entry must be larger. */
    public StreamId lastId() {
        return lastId;
    }

    /** The recorded first entry id ({@code 0-0} for an empty stream). */
    public StreamId firstId() {
        return firstId;
    }

    public StreamId maxDeletedId() {
        return maxDeletedId;
    }

    public long entriesAdded() {
        return entriesAdded;
    }

    /** The id of the oldest entry, or {@code null} if there is none. */
    public StreamId firstEntryId() {
        return log.first();
    }

    /** The id of the newest entry, or {@code null} if there is none ({@code lastId} may name a deleted one). */
    public StreamId lastEntryId() {
        return log.last();
    }

    /** How many blocks the entries are stored in. */
    public int blockCount() {
        return log.blockCount();
    }

    // -------------------------------------------------------------------- add

    /**
     * The id a new entry would get - the one rule shared by {@link #add} and by the code that writes the id into the
     * command before it is logged and replicated.
     *
     * @param requested the id the client asked for, {@code null} for {@code *}
     * @param seqGiven  {@code false} for {@code ms-*}: the sequence is chosen
     * @throws StreamException if the id is not larger than {@code last}
     */
    public static StreamId nextId(StreamId last, StreamId requested, boolean seqGiven, long now) {
        StreamId id;
        if (requested == null) {
            if (Long.compareUnsigned(now, last.ms()) > 0) {
                id = new StreamId(now, 0);
            } else {
                id = last.next();
            }
        } else if (seqGiven) {
            id = requested;
        } else if (last.ms() == requested.ms()) {
            id = last.seq() == -1L ? null : new StreamId(requested.ms(), last.seq() + 1);
        } else {
            id = new StreamId(requested.ms(), 0);
        }
        if (id == null || id.compareTo(last) <= 0) {
            throw new StreamException("The ID specified in XADD is equal or smaller than the target stream top item");
        }
        return id;
    }

    /**
     * Appends an entry.
     *
     * @param requested the id the client asked for, {@code null} for {@code *}
     * @param seqGiven  {@code false} for {@code ms-*}
     * @param args      the field names and values, alternating, from index {@code from}
     * @return the id of the new entry
     */
    public StreamId add(StreamId requested, boolean seqGiven, List<String> args, int from, long now) {
        if (lastId.equals(StreamId.MAX)) {
            throw new StreamException("The stream has exhausted the last possible ID, unable to add more items");
        }
        StreamId id = nextId(lastId, requested, seqGiven, now);
        log.append(id.ms(), id.seq(), args, from, (args.size() - from) / 2);
        entriesAdded++;
        lastId = id;
        if (log.size() == 1) {
            firstId = id;
        }
        return id;
    }

    // --------------------------------------------------------------- removing

    /**
     * Removes the oldest entries until {@code maxLen} are left. Exact: Redis's {@code ~} may stop early at a
     * node boundary, this does not, so a stream is never longer than asked.
     *
     * @param limit the most entries to remove, {@code 0} for no limit
     * @return how many entries were removed
     */
    public long trimByLength(long maxLen, long limit) {
        long removed = log.trimToLength(maxLen, limit);
        afterTrim(removed);
        return removed;
    }

    /**
     * Removes the entries with an id below {@code minId}.
     *
     * @param limit the most entries to remove, {@code 0} for no limit
     * @return how many entries were removed
     */
    public long trimByMinId(StreamId minId, long limit) {
        long removed = log.trimBefore(minId, limit);
        afterTrim(removed);
        return removed;
    }

    private void afterTrim(long removed) {
        if (log.size() == 0) {
            firstId = StreamId.ZERO;
        } else if (removed > 0) {
            firstId = log.first();
        }
    }

    /** {@code XDEL}: removes these entries (those that exist) and returns how many there were. */
    public int delete(List<StreamId> ids) {
        int deleted = 0;
        boolean firstGone = false;
        for (StreamId id : ids) {
            if (log.delete(id.ms(), id.seq())) {
                if (id.equals(firstId)) {
                    firstGone = true;
                }
                if (id.compareTo(maxDeletedId) > 0) {
                    maxDeletedId = id;
                }
                deleted++;
            }
        }
        if (deleted > 0) {
            if (log.size() == 0) {
                firstId = StreamId.ZERO;
            } else if (firstGone) {
                firstId = log.first();
            }
        }
        return deleted;
    }

    /**
     * {@code XSETID}: sets the last id and, if given, the counters. It is what a snapshot uses to put back the part
     * of the stream's history that no entry shows (deleted ids, how many were ever added).
     *
     * @param entriesAdded the new count, -1 to keep it
     * @param maxDeleted   the new largest deleted id, {@code 0-0} to keep it
     */
    public void setId(StreamId id, long entriesAdded, StreamId maxDeleted) {
        if (id.compareTo(maxDeletedId) < 0) {
            throw new StreamException("The ID specified in XSETID is smaller than current max_deleted_entry_id");
        }
        if (log.size() > 0) {
            if (id.compareTo(log.last()) < 0) {
                throw new StreamException("The ID specified in XSETID is smaller than the target stream top item");
            }
            if (entriesAdded != -1 && log.size() > entriesAdded) {
                throw new StreamException(
                        "The entries_added specified in XSETID is smaller than the target stream length");
            }
        }
        lastId = id;
        if (entriesAdded != -1) {
            this.entriesAdded = entriesAdded;
        }
        if (!maxDeleted.isZero()) {
            maxDeletedId = maxDeleted;
        }
    }

    // ---------------------------------------------------------------- reading

    /**
     * The entries from {@code start} to {@code end}, both included.
     *
     * @param reverse newest first
     * @param count   the most entries to return, {@code 0} for all
     */
    public List<StreamEntry> range(StreamId start, StreamId end, boolean reverse, long count) {
        return log.range(start, end, reverse, count);
    }

    /** @return the entry, or {@code null} if there is none with this id */
    public StreamEntry entry(StreamId id) {
        return log.get(id.ms(), id.seq());
    }

    /** All entries, oldest first. */
    public Iterator<StreamEntry> entries() {
        return log.iterator();
    }

    // ----------------------------------------------------------------- groups

    /** @return the group, or {@code null} */
    public ConsumerGroup group(String name) {
        return groups.get(name);
    }

    /** The groups by name (read only). */
    public Collection<ConsumerGroup> groups() {
        return Collections.unmodifiableCollection(groups.values());
    }

    public int groupCount() {
        return groups.size();
    }

    /**
     * Creates a group.
     *
     * @param entriesRead the group's read counter, -1 if unknown
     * @return the group, or {@code null} if there already is one with this name
     */
    public ConsumerGroup createGroup(String name, StreamId lastId, long entriesRead) {
        if (groups.containsKey(name)) {
            return null;
        }
        ConsumerGroup group = new ConsumerGroup(name, lastId, entriesRead);
        groups.put(name, group);
        groupBytes += GROUP_BYTES + NAME_BYTES + name.length();
        return group;
    }

    /** @return whether there was such a group */
    public boolean destroyGroup(String name) {
        ConsumerGroup group = groups.remove(name);
        if (group == null) {
            return false;
        }
        groupBytes -= GROUP_BYTES + NAME_BYTES + name.length() + group.pel.size() * PENDING_BYTES;
        for (Consumer consumer : group.consumers.values()) {
            groupBytes -= CONSUMER_BYTES + NAME_BYTES + consumer.name.length();
        }
        return true;
    }

    /** {@code XGROUP SETID}: moves the group's position (and its read counter, -1 if unknown). */
    public void setGroupPosition(ConsumerGroup group, StreamId lastId, long entriesRead) {
        group.lastId = lastId;
        group.entriesRead = entriesRead;
    }

    /** @return the new consumer, or {@code null} if the group already has one with this name */
    public Consumer createConsumer(ConsumerGroup group, String name, long now) {
        if (group.consumers.containsKey(name)) {
            return null;
        }
        Consumer consumer = new Consumer(name, now);
        group.consumers.put(name, consumer);
        groupBytes += CONSUMER_BYTES + NAME_BYTES + name.length();
        return consumer;
    }

    /** Notes that the consumer tried to read or claim something just now ({@code seen-time}). */
    public void touch(Consumer consumer, long now) {
        consumer.seenTime = now;
    }

    /**
     * Removes a consumer <em>and the entries it still had pending</em> (they are forgotten, not handed to anybody -
     * claim them first if that is not wanted).
     *
     * @return how many pending entries went with it
     */
    public long deleteConsumer(ConsumerGroup group, Consumer consumer) {
        long pending = consumer.pel.size();
        for (StreamId id : consumer.pel.keySet()) {
            group.pel.remove(id);
        }
        groupBytes -= pending * PENDING_BYTES;
        consumer.pel.clear();
        group.consumers.remove(consumer.name);
        groupBytes -= CONSUMER_BYTES + NAME_BYTES + consumer.name.length();
        return pending;
    }

    // ------------------------------------------------------- delivering ("> ")

    /** One entry handed to a consumer by a {@code >} read, with what the pending list says about it afterwards. */
    public record Delivery(StreamEntry entry, long deliveryTime, long deliveryCount, StreamId groupLastId) {
    }

    /** What a {@code >} read did. */
    public record DeliveryResult(List<Delivery> deliveries, boolean positionMoved, StreamId lastId, long entriesRead) {
    }

    /**
     * {@code XREADGROUP ... >}: hands the consumer the entries after the group's position, moves the position and the
     * read counter, and - unless {@code noack} - puts each entry in the pending lists (taking it over from
     * whoever had it, which can happen after {@code XGROUP SETID} went back).
     *
     * @param count the most entries, {@code 0} for all
     */
    public DeliveryResult deliverNew(ConsumerGroup group, Consumer consumer, long count, boolean noack, long now) {
        List<Delivery> deliveries = new ArrayList<>();
        boolean moved = false;
        StreamId start = group.lastId.next();
        if (start != null && log.size() > 0) {
            for (StreamEntry entry : log.range(start, StreamId.MAX, false, count)) {
                StreamId id = entry.id();
                if (group.entriesRead != -1 && !rangeHasTombstones(group.lastId, null)) {
                    group.entriesRead++;
                } else if (entriesAdded != 0) {
                    group.entriesRead = estimateDistanceFromFirstEverEntry(id);
                }
                group.lastId = id;
                moved = true;
                if (!noack) {
                    Pending pending = group.pel.get(id);
                    if (pending == null) {
                        pending = new Pending(consumer, now, 1);
                        group.pel.put(id, pending);
                        groupBytes += PENDING_BYTES;
                    } else {
                        pending.consumer.pel.remove(id);
                        pending.consumer = consumer;
                        pending.deliveryTime = now;
                        pending.deliveryCount = 1;
                    }
                    consumer.pel.put(id, pending);
                    consumer.activeTime = now;
                }
                deliveries.add(new Delivery(entry, now, 1, id));
            }
        }
        return new DeliveryResult(deliveries, moved, group.lastId, group.entriesRead);
    }

    /** An entry of a consumer's own history; {@code entry} is {@code null} if it was deleted from the stream. */
    public record HistoryItem(StreamId id, StreamEntry entry, long deliveryTime, long deliveryCount) {
    }

    /**
     * {@code XREADGROUP ... <id>}: the consumer's own pending entries from {@code start} on. Each one that still exists
     * counts as delivered again (time and count are updated); one that was deleted is reported without fields.
     *
     * @param count the most entries, {@code 0} for all
     */
    public List<HistoryItem> deliverHistory(Consumer consumer, StreamId start, long count, long now) {
        List<HistoryItem> items = new ArrayList<>();
        for (Map.Entry<StreamId, Pending> held : consumer.pel.tailMap(start, true).entrySet()) {
            if (count != 0 && items.size() >= count) {
                break;
            }
            StreamId id = held.getKey();
            Pending pending = held.getValue();
            StreamEntry entry = log.get(id.ms(), id.seq());
            if (entry == null) {
                items.add(new HistoryItem(id, null, pending.deliveryTime, pending.deliveryCount));
            } else {
                pending.deliveryTime = now;
                pending.deliveryCount++;
                items.add(new HistoryItem(id, entry, now, pending.deliveryCount));
            }
        }
        return items;
    }

    // ------------------------------------------------------------ ack / claim

    /** {@code XACK}: forgets these pending entries. @return how many of them were pending */
    public long ack(ConsumerGroup group, List<StreamId> ids) {
        long acknowledged = 0;
        for (StreamId id : ids) {
            Pending pending = group.pel.remove(id);
            if (pending != null) {
                pending.consumer.pel.remove(id);
                groupBytes -= PENDING_BYTES;
                acknowledged++;
            }
        }
        return acknowledged;
    }

    /**
     * The options of {@code XCLAIM}.
     *
     * @param minIdle      claim only entries not delivered for this long (ms)
     * @param deliveryTime the delivery time to record, -1 for now (already turned from {@code IDLE} into a time)
     * @param retryCount   the delivery counter to set, -1 to leave it (or add one, unless {@code justId})
     * @param force        create the pending entry if it is not pending (the entry must exist)
     * @param justId       do not count a delivery
     * @param lastId       raise the group's position to this, {@code 0-0} for no change
     */
    public record ClaimOptions(
            long minIdle, long deliveryTime, long retryCount, boolean force, boolean justId, StreamId lastId) {
    }

    /** One entry that changed hands. {@code entry} is {@code null} when the command asked for ids only. */
    public record Claimed(StreamId id, StreamEntry entry, long deliveryTime, long deliveryCount) {
    }

    /**
     * What a claim did.
     *
     * @param dropped         pending entries whose message no longer exists; they were removed
     * @param consumerCreated the claiming consumer did not exist
     * @param positionMoved   the group's position moved up ({@code LASTID})
     */
    public record ClaimResult(
            List<Claimed> claimed, List<StreamId> dropped, boolean consumerCreated, boolean positionMoved) {
    }

    /** {@code XCLAIM}: gives the entries to the consumer if they are pending long enough. */
    public ClaimResult claim(
            ConsumerGroup group, String consumerName, List<StreamId> ids, ClaimOptions options, long now) {
        boolean moved = false;
        if (options.lastId().compareTo(group.lastId) > 0) {
            group.lastId = options.lastId();
            moved = true;
        }
        long deliveryTime = options.deliveryTime();
        if (deliveryTime != -1) {
            // a bogus value (negative, or in the future) is not worth an error: clients compute it from their own clock
            if (deliveryTime < 0 || deliveryTime > now) {
                deliveryTime = now;
            }
        } else {
            deliveryTime = now;
        }

        Consumer consumer = group.consumers.get(consumerName);
        boolean created = consumer == null;
        if (created) {
            consumer = createConsumer(group, consumerName, now);
        }
        consumer.seenTime = now;

        List<Claimed> claimed = new ArrayList<>();
        List<StreamId> dropped = new ArrayList<>();
        for (StreamId id : ids) {
            Pending pending = group.pel.get(id);
            if (!log.contains(id.ms(), id.seq())) {
                if (pending != null) { // pending, but the message is gone: it cannot be claimed, forget it
                    group.pel.remove(id);
                    pending.consumer.pel.remove(id);
                    groupBytes -= PENDING_BYTES;
                    dropped.add(id);
                }
                continue;
            }
            if (options.force() && pending == null) {
                pending = new Pending(null, now, 1);
                group.pel.put(id, pending);
                groupBytes += PENDING_BYTES;
            }
            if (pending == null) {
                continue;
            }
            if (pending.consumer != null && options.minIdle() != 0 && now - pending.deliveryTime < options.minIdle()) {
                continue;
            }
            if (pending.consumer != consumer && pending.consumer != null) {
                pending.consumer.pel.remove(id);
            }
            pending.deliveryTime = deliveryTime;
            if (options.retryCount() >= 0) {
                pending.deliveryCount = options.retryCount();
            } else if (!options.justId()) {
                pending.deliveryCount++;
            }
            if (pending.consumer != consumer) {
                consumer.pel.put(id, pending);
                pending.consumer = consumer;
            }
            consumer.activeTime = now;
            claimed.add(new Claimed(id, options.justId() ? null : log.get(id.ms(), id.seq()),
                    pending.deliveryTime, pending.deliveryCount));
        }
        return new ClaimResult(claimed, dropped, created, moved);
    }

    /**
     * What {@code XAUTOCLAIM} did.
     *
     * @param next the id to continue from, {@code 0-0} when the whole list was looked at
     */
    public record AutoClaimResult(
            StreamId next, List<Claimed> claimed, List<StreamId> deleted, boolean consumerCreated) {
    }

    /**
     * {@code XAUTOCLAIM}: walks the group's pending list from {@code start} and gives the consumer the entries that have
     * been pending for {@code minIdle} or longer. At most {@code count} entries are claimed or dropped, and at
     * most ten times as many are looked at, so one call never scans an unbounded list.
     */
    public AutoClaimResult autoClaim(
            ConsumerGroup group, String consumerName, long minIdle, StreamId start, long count, boolean justId,
            long now) {
        Consumer consumer = group.consumers.get(consumerName);
        boolean created = consumer == null;
        if (created) {
            consumer = createConsumer(group, consumerName, now);
        }
        consumer.seenTime = now;

        List<Claimed> claimed = new ArrayList<>();
        List<StreamId> deleted = new ArrayList<>();
        long attempts = count * AUTOCLAIM_ATTEMPTS_FACTOR;
        long left = count;
        StreamId cursor = start;
        while (attempts-- > 0 && left > 0 && cursor != null) {
            Map.Entry<StreamId, Pending> held = group.pel.ceilingEntry(cursor);
            if (held == null) {
                cursor = null;
                break;
            }
            StreamId id = held.getKey();
            Pending pending = held.getValue();
            cursor = id.next();

            if (!log.contains(id.ms(), id.seq())) {
                group.pel.remove(id);
                pending.consumer.pel.remove(id);
                groupBytes -= PENDING_BYTES;
                deleted.add(id);
                left--;
                continue;
            }
            if (minIdle != 0 && now - pending.deliveryTime < minIdle) {
                continue;
            }
            if (pending.consumer != consumer) {
                pending.consumer.pel.remove(id);
            }
            pending.deliveryTime = now;
            if (!justId) {
                pending.deliveryCount++;
            }
            if (pending.consumer != consumer) {
                consumer.pel.put(id, pending);
                pending.consumer = consumer;
            }
            consumer.activeTime = now;
            claimed.add(new Claimed(id, justId ? null : log.get(id.ms(), id.seq()),
                    pending.deliveryTime, pending.deliveryCount));
            left--;
        }
        Map.Entry<StreamId, Pending> following = cursor == null ? null : group.pel.ceilingEntry(cursor);
        return new AutoClaimResult(following == null ? StreamId.ZERO : following.getKey(), claimed, deleted, created);
    }

    /**
     * Puts one pending entry back exactly as a snapshot recorded it ({@code XRESTORE}): to this consumer (created if need
     * be), with this delivery time and count. Unlike {@code XCLAIM ... FORCE} it does not ask whether the message
     * still exists - a pending entry may outlive its message ({@code XDEL}, {@code XTRIM}), and a snapshot must not
     * lose it. An entry that is already pending is taken over.
     */
    public void restorePending(ConsumerGroup group, String consumerName, StreamId id, long time, long count, long now) {
        Consumer consumer = group.consumers.get(consumerName);
        if (consumer == null) {
            consumer = createConsumer(group, consumerName, now);
        }
        Pending pending = group.pel.get(id);
        if (pending == null) {
            pending = new Pending(consumer, time, count);
            group.pel.put(id, pending);
            groupBytes += PENDING_BYTES;
        } else {
            pending.consumer.pel.remove(id);
            pending.consumer = consumer;
            pending.deliveryTime = time;
            pending.deliveryCount = count;
        }
        consumer.pel.put(id, pending);
    }

    // ------------------------------------------------------- pending overview

    /** What {@code XPENDING key group} shows. */
    public record PendingSummary(long total, StreamId first, StreamId last, List<Map.Entry<String, Long>> perConsumer) {
    }

    public PendingSummary pendingSummary(ConsumerGroup group) {
        List<Map.Entry<String, Long>> perConsumer = new ArrayList<>();
        for (Consumer consumer : group.consumers.values()) {
            if (!consumer.pel.isEmpty()) {
                perConsumer.add(Map.entry(consumer.name, (long) consumer.pel.size()));
            }
        }
        if (group.pel.isEmpty()) {
            return new PendingSummary(0, null, null, perConsumer);
        }
        return new PendingSummary(group.pel.size(), group.pel.firstKey(), group.pel.lastKey(), perConsumer);
    }

    /** One line of {@code XPENDING key group start end count}. */
    public record PendingItem(StreamId id, String consumer, long idle, long deliveryCount) {
    }

    /**
     * The pending entries with an id from {@code start} to {@code end}, of one consumer or of the whole group.
     *
     * @param minIdle only entries pending at least this long (ms), 0 for all
     */
    public List<PendingItem> pendingRange(
            ConsumerGroup group, Consumer only, StreamId start, StreamId end, long count, long minIdle, long now) {
        List<PendingItem> items = new ArrayList<>();
        if (count <= 0 || start.compareTo(end) > 0) {
            return items;
        }
        for (Map.Entry<StreamId, Pending> held
                : (only != null ? only.pel : group.pel).subMap(start, true, end, true).entrySet()) {
            Pending pending = held.getValue();
            long idle = Math.max(0, now - pending.deliveryTime);
            if (minIdle != 0 && now - pending.deliveryTime < minIdle) {
                continue;
            }
            items.add(new PendingItem(held.getKey(), pending.consumer.name, idle, pending.deliveryCount));
            if (items.size() >= count) {
                break;
            }
        }
        return items;
    }

    // ------------------------------------------------------------- lag & co.

    /** Whether the ids in {@code [start, end]} (end {@code null}: no end) include one that was deleted. */
    private boolean rangeHasTombstones(StreamId start, StreamId end) {
        if (log.size() == 0 || maxDeletedId.isZero()) {
            return false;
        }
        StreamId from = start != null ? start : StreamId.ZERO;
        StreamId to = end != null ? end : StreamId.MAX;
        return from.compareTo(maxDeletedId) <= 0 && maxDeletedId.compareTo(to) <= 0;
    }

    /**
     * How many entries were added up to and including this id, if that can be told: the count of an id is known
     * for the last id, for the first entry and for anything before it, as long as nothing was deleted in between.
     *
     * @return the count, or -1 when it cannot be known
     */
    private long estimateDistanceFromFirstEverEntry(StreamId id) {
        if (entriesAdded == 0) {
            return 0;
        }
        if (log.size() == 0 && id.compareTo(lastId) < 1) {
            return entriesAdded;
        }
        if (!id.isZero() && id.compareTo(maxDeletedId) < 0) {
            return -1;
        }
        int againstLast = id.compareTo(lastId);
        if (againstLast == 0) {
            return entriesAdded;
        }
        if (againstLast > 0) {
            return -1;
        }
        int againstFirst = id.compareTo(firstId);
        if (maxDeletedId.isZero() || maxDeletedId.compareTo(firstId) < 0) {
            if (againstFirst < 0) {
                return entriesAdded - log.size();
            }
            if (againstFirst == 0) {
                return entriesAdded - log.size() + 1;
            }
        }
        return -1;
    }

    /**
     * How many entries the group has not been given yet ({@code XINFO}'s {@code lag}).
     *
     * @return the number, or -1 when it cannot be told because entries were deleted behind the group's position
     */
    public long lag(ConsumerGroup group) {
        if (entriesAdded == 0) {
            return 0;
        }
        if (log.size() == 0) {
            return 0;
        }
        if (group.lastId.compareTo(firstId) < 0 && maxDeletedId.compareTo(firstId) < 0) {
            return log.size();
        }
        if (group.entriesRead != -1 && !rangeHasTombstones(group.lastId, null)) {
            return entriesAdded - group.entriesRead;
        }
        long read = estimateDistanceFromFirstEverEntry(group.lastId);
        return read != -1 ? entriesAdded - read : -1;
    }

    /**
     * Whether a {@code >} read by this group would find an entry; the check a blocked reader makes before it spends a
     * trip through the write path.
     */
    public boolean hasUndelivered(ConsumerGroup group) {
        StreamId last = log.last();
        return last != null && last.compareTo(group.lastId) > 0;
    }

    // ---------------------------------------------------------------- memory

    /** The estimated heap size, kept up to date by every change ({@code maxmemory} counts it). */
    public long estimatedBytes() {
        return BASE_BYTES + log.bytes() + groupBytes;
    }

    /** The estimate added up from scratch; tests compare it with {@link #estimatedBytes()}. */
    public long recountBytes() {
        long total = BASE_BYTES + log.recountBytes();
        for (ConsumerGroup group : groups.values()) {
            total += GROUP_BYTES + NAME_BYTES + group.name.length() + group.pel.size() * PENDING_BYTES;
            for (Consumer consumer : group.consumers.values()) {
                total += CONSUMER_BYTES + NAME_BYTES + consumer.name.length();
            }
        }
        return total;
    }

    /** The entry count, counted slot by slot; tests compare it with {@link #length()}. */
    public long recountLength() {
        return log.recountSize();
    }

    // -------------------------------------------------------------- snapshots

    /**
     * A private copy, for a snapshot (AOF rewrite, full sync): the entries are shared - they are never modified -
     * and the groups are copied, so the copy can be turned into commands after the key's lock has been released.
     */
    public Stream copy() {
        Stream copy = new Stream(log.copy());
        copy.lastId = lastId;
        copy.firstId = firstId;
        copy.maxDeletedId = maxDeletedId;
        copy.entriesAdded = entriesAdded;
        copy.groupBytes = groupBytes;
        for (ConsumerGroup group : groups.values()) {
            ConsumerGroup groupCopy = new ConsumerGroup(group.name, group.lastId, group.entriesRead);
            for (Consumer consumer : group.consumers.values()) {
                Consumer consumerCopy = new Consumer(consumer.name, consumer.seenTime);
                consumerCopy.activeTime = consumer.activeTime;
                groupCopy.consumers.put(consumer.name, consumerCopy);
            }
            for (Map.Entry<StreamId, Pending> held : group.pel.entrySet()) {
                Pending pending = held.getValue();
                Pending pendingCopy = new Pending(
                        groupCopy.consumers.get(pending.consumer.name), pending.deliveryTime, pending.deliveryCount);
                groupCopy.pel.put(held.getKey(), pendingCopy);
                pendingCopy.consumer.pel.put(held.getKey(), pendingCopy);
            }
            copy.groups.put(group.name, groupCopy);
        }
        return copy;
    }

    /**
     * The commands that rebuild this stream under {@code key}, produced one at a time:
     * <ol>
     *   <li>{@code XADD key <id> field value ...} for every entry, oldest first - or, for a stream without entries,
     *       {@code XADD key MAXLEN 0 1-1 x y}, which creates the key and leaves it empty;</li>
     *   <li>{@code XSETID key <last id> ENTRIESADDED <n> [MAXDELETEDID <id>]}, the history no entry shows;</li>
     *   <li>per group {@code XGROUP CREATE key <group> <id> ENTRIESREAD <n>}, {@code XGROUP CREATECONSUMER}, and
     *       {@code XRESTORE key <group> <consumer> <id> <delivery time> <delivery count>} for every pending entry.</li>
     * </ol>
     * {@code XRESTORE} (internal, admin only) and not {@code XCLAIM ... FORCE}, because {@code XCLAIM} cannot put back
     * a pending entry whose message was deleted - Redis's own AOF rewrite loses those, a snapshot here keeps
     * them, so a restart, a full sync and a failover leave the groups exactly as they were. Call this on a
     * {@link #copy()}.
     */
    public Iterator<List<String>> snapshotCommands(String key) {
        Deque<Supplier<Iterator<List<String>>>> parts = new ArrayDeque<>();
        if (log.size() == 0) {
            parts.add(() -> List.of(List.of("XADD", key, "MAXLEN", "0", "1-1", "x", "y")).iterator());
        } else {
            parts.add(() -> new Iterator<>() {
                private final Iterator<StreamEntry> entries = log.iterator();

                @Override
                public boolean hasNext() {
                    return entries.hasNext();
                }

                @Override
                public List<String> next() {
                    StreamEntry entry = entries.next();
                    List<String> command = new ArrayList<>(3 + entry.fields().length);
                    command.add("XADD");
                    command.add(key);
                    command.add(entry.id().toString());
                    Collections.addAll(command, entry.fields());
                    return command;
                }
            });
        }
        parts.add(() -> {
            List<String> command = new ArrayList<>(List.of("XSETID", key, lastId.toString(),
                    "ENTRIESADDED", Long.toString(entriesAdded)));
            if (!maxDeletedId.isZero()) {
                command.add("MAXDELETEDID");
                command.add(maxDeletedId.toString());
            }
            return List.of(command).iterator();
        });
        for (ConsumerGroup group : groups.values()) {
            parts.add(() -> groupCommands(key, group));
        }
        return new Chain(parts);
    }

    private static Iterator<List<String>> groupCommands(String key, ConsumerGroup group) {
        Deque<Supplier<Iterator<List<String>>>> parts = new ArrayDeque<>();
        parts.add(() -> List.of(List.of("XGROUP", "CREATE", key, group.name, group.lastId.toString(),
                "ENTRIESREAD", Long.toString(group.entriesRead))).iterator());
        parts.add(() -> {
            List<List<String>> commands = new ArrayList<>();
            for (Consumer consumer : group.consumers.values()) {
                commands.add(List.of("XGROUP", "CREATECONSUMER", key, group.name, consumer.name));
            }
            return commands.iterator();
        });
        parts.add(() -> new Iterator<>() {
            private final Iterator<Map.Entry<StreamId, Pending>> held = group.pel.entrySet().iterator();

            @Override
            public boolean hasNext() {
                return held.hasNext();
            }

            @Override
            public List<String> next() {
                Map.Entry<StreamId, Pending> entry = held.next();
                Pending pending = entry.getValue();
                return List.of("XRESTORE", key, group.name, pending.consumer.name, entry.getKey().toString(),
                        Long.toString(pending.deliveryTime), Long.toString(pending.deliveryCount));
            }
        });
        return new Chain(parts);
    }

    /** Several iterators one after the other, each created only when the one before it is used up. */
    private static final class Chain implements Iterator<List<String>> {
        private final Deque<Supplier<Iterator<List<String>>>> parts;
        private Iterator<List<String>> current;

        Chain(Deque<Supplier<Iterator<List<String>>>> parts) {
            this.parts = parts;
        }

        @Override
        public boolean hasNext() {
            while (current == null || !current.hasNext()) {
                if (parts.isEmpty()) {
                    return false;
                }
                current = parts.poll().get();
            }
            return true;
        }

        @Override
        public List<String> next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return current.next();
        }
    }
}
