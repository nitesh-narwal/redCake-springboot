package me.niteshh.redcake.stream;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * The entries of one stream, oldest first.
 *
 * <h3>Layout</h3>
 * The entries live in <em>blocks</em> of up to {@value #BLOCK_MAX}: three parallel arrays (the two halves of the id,
 * and the entry itself) and the field names of the block's first entry. Ids only ever increase, so a lookup is a
 * binary search over the blocks and then inside one block; an append touches the last block only; trimming from the
 * head drops whole blocks. There is no node per entry (a {@code TreeMap} costs ~100 bytes per entry before the entry
 * is stored at all) and no growth by copying the whole log.
 *
 * <p>An entry is one {@code byte[]}: a flag byte, then the values (each a length and its bytes). The field names are
 * not stored again when they are the block's own - which is the case for nearly every entry of a real stream, where
 * every record has the same shape - so a record such as {@code task=t-12 payload=...} costs its values plus a few
 * bytes. An entry with other names stores them inline (flag {@code CUSTOM}). Values are byte strings (one char = one
 * byte), so {@code String.length()} is the byte length and the entry round-trips exactly.
 *
 * <p>A deleted entry leaves a {@code null} slot (its id stays, so the binary search stays valid). A block that is
 * mostly holes is compacted; a block without entries is dropped.
 *
 * <p>Not thread-safe: the store's per-key lock protects it.
 */
final class EntryLog {

    /** Most entries in a block. */
    static final int BLOCK_MAX = 128;
    private static final int FIRST_CAPACITY = 4;

    /** A block is compacted when it has at least this many holes and they are more than half of its slots. */
    private static final int COMPACT_HOLES = 16;

    private static final byte SAME_NAMES = 1;
    private static final byte OWN_NAMES = 0;

    // The memory model: JVM-ish estimates, generous. The same helpers add and subtract, and tests recount.
    private static final long SLOT = 20;
    private static final long BLOCK_BASE = 112;
    private static final long ARRAY_HEADER = 16;
    private static final long STRING_BASE = 40;

    private final ArrayList<Block> blocks = new ArrayList<>();
    private long size;
    private long bytes;

    /** One block. */
    private static final class Block {
        long[] ms;
        long[] seq;
        byte[][] data;
        /** Slots in use, holes included. */
        int count;
        /** Entries (slots that are not holes). */
        int live;
        /** Every slot before this one is a hole (a hint that makes "the oldest entry" cheap after trimming). */
        int head;
        /** The field names of the block's first entry, shared by every entry whose names are the same. */
        final String[] master;
        final long masterBytes;
        /** The estimated size of the live entries. */
        long dataBytes;

        Block(String[] master, int capacity) {
            this.master = master;
            this.ms = new long[capacity];
            this.seq = new long[capacity];
            this.data = new byte[capacity][];
            long names = ARRAY_HEADER + 4L * master.length;
            for (String name : master) {
                names += STRING_BASE + name.length();
            }
            this.masterBytes = names;
        }

        long footprint() {
            return BLOCK_BASE + (long) ms.length * SLOT + masterBytes;
        }

        /** First slot whose id is not smaller than the given one ({@code count} if there is none). */
        int lowerBound(long m, long s) {
            int low = 0;
            int high = count;
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (StreamId.compare(ms[mid], seq[mid], m, s) < 0) {
                    low = mid + 1;
                } else {
                    high = mid;
                }
            }
            return low;
        }

        /** First slot whose id is larger than the given one ({@code count} if there is none). */
        int upperBound(long m, long s) {
            int low = 0;
            int high = count;
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (StreamId.compare(ms[mid], seq[mid], m, s) <= 0) {
                    low = mid + 1;
                } else {
                    high = mid;
                }
            }
            return low;
        }

        /** The slot holding this id, or -1. */
        int find(long m, long s) {
            int slot = lowerBound(m, s);
            return slot < count && ms[slot] == m && seq[slot] == s ? slot : -1;
        }

        /** The first slot that holds an entry, or -1. */
        int firstLive() {
            while (head < count && data[head] == null) {
                head++;
            }
            return head < count ? head : -1;
        }

        int lastLive() {
            for (int slot = count - 1; slot >= head; slot--) {
                if (data[slot] != null) {
                    return slot;
                }
            }
            return -1;
        }

        /** @return the change of the block's estimated size */
        long add(long m, long s, byte[] packed) {
            long delta = 0;
            if (count == ms.length) {
                int capacity = Math.min(BLOCK_MAX, ms.length * 2);
                delta += (long) (capacity - ms.length) * SLOT;
                ms = Arrays.copyOf(ms, capacity);
                seq = Arrays.copyOf(seq, capacity);
                data = Arrays.copyOf(data, capacity);
            }
            ms[count] = m;
            seq[count] = s;
            data[count] = packed;
            count++;
            live++;
            long cost = entryCost(packed);
            dataBytes += cost;
            return delta + cost;
        }

        /** @return the change of the block's estimated size */
        long remove(int slot) {
            long cost = entryCost(data[slot]);
            data[slot] = null;
            live--;
            dataBytes -= cost;
            return -cost;
        }

        boolean sparse() {
            return count - live >= COMPACT_HOLES && live * 2 < count;
        }

        /** Squeezes the holes out. @return the change of the block's estimated size */
        long compact() {
            long before = footprint();
            int capacity = FIRST_CAPACITY;
            while (capacity < live) {
                capacity <<= 1;
            }
            long[] newMs = new long[capacity];
            long[] newSeq = new long[capacity];
            byte[][] newData = new byte[capacity][];
            int n = 0;
            for (int slot = 0; slot < count; slot++) {
                if (data[slot] != null) {
                    newMs[n] = ms[slot];
                    newSeq[n] = seq[slot];
                    newData[n] = data[slot];
                    n++;
                }
            }
            ms = newMs;
            seq = newSeq;
            data = newData;
            count = n;
            head = 0;
            return footprint() - before;
        }

        Block copy() {
            Block copy = new Block(master, Math.max(FIRST_CAPACITY, count));
            System.arraycopy(ms, 0, copy.ms, 0, count);
            System.arraycopy(seq, 0, copy.seq, 0, count);
            System.arraycopy(data, 0, copy.data, 0, count); // the entries are never modified: shared
            copy.count = count;
            copy.live = live;
            copy.head = head;
            copy.dataBytes = dataBytes;
            return copy;
        }
    }

    // ------------------------------------------------------------------ size

    long size() {
        return size;
    }

    /** The estimated heap size of the log. */
    long bytes() {
        return bytes;
    }

    int blockCount() {
        return blocks.size();
    }

    // ---------------------------------------------------------------- append

    /**
     * Adds an entry with an id larger than every id so far (the caller checks). {@code args[from ...]} are
     * {@code pairs} field names and values, alternating.
     */
    void append(long ms, long seq, List<String> args, int from, int pairs) {
        Block tail = blocks.isEmpty() ? null : blocks.get(blocks.size() - 1);
        if (tail != null && tail.count == BLOCK_MAX) {
            if (tail.live * 2 <= BLOCK_MAX) {
                bytes += tail.compact();
            } else {
                tail = null;
            }
        }
        if (tail == null) {
            String[] names = new String[pairs];
            for (int i = 0; i < pairs; i++) {
                names[i] = args.get(from + 2 * i);
            }
            tail = new Block(names, FIRST_CAPACITY);
            blocks.add(tail);
            bytes += tail.footprint();
        }
        bytes += tail.add(ms, seq, pack(tail.master, args, from, pairs));
        size++;
    }

    // --------------------------------------------------------------- lookups

    /** @return the index of the last block that starts at or before this id, or -1 if it is before all of them */
    private int blockIndexFor(long m, long s) {
        int low = 0;
        int high = blocks.size() - 1;
        int found = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            Block block = blocks.get(mid);
            if (StreamId.compare(block.ms[0], block.seq[0], m, s) <= 0) {
                found = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return found;
    }

    boolean contains(long ms, long seq) {
        int bi = blockIndexFor(ms, seq);
        if (bi < 0) {
            return false;
        }
        Block block = blocks.get(bi);
        int slot = block.find(ms, seq);
        return slot >= 0 && block.data[slot] != null;
    }

    /** @return the entry with this id, or {@code null} */
    StreamEntry get(long ms, long seq) {
        int bi = blockIndexFor(ms, seq);
        if (bi < 0) {
            return null;
        }
        Block block = blocks.get(bi);
        int slot = block.find(ms, seq);
        return slot < 0 || block.data[slot] == null ? null : decode(block, slot);
    }

    /** @return the id of the oldest entry, or {@code null} */
    StreamId first() {
        for (Block block : blocks) {
            int slot = block.firstLive();
            if (slot >= 0) {
                return new StreamId(block.ms[slot], block.seq[slot]);
            }
        }
        return null;
    }

    /** @return the id of the newest entry, or {@code null} */
    StreamId last() {
        for (int i = blocks.size() - 1; i >= 0; i--) {
            Block block = blocks.get(i);
            int slot = block.lastLive();
            if (slot >= 0) {
                return new StreamId(block.ms[slot], block.seq[slot]);
            }
        }
        return null;
    }

    /**
     * The entries from {@code start} to {@code end}, both included, oldest first - or newest first when
     * {@code reverse} - at most {@code limit} of them ({@code 0}: all).
     */
    List<StreamEntry> range(StreamId start, StreamId end, boolean reverse, long limit) {
        List<StreamEntry> out = new ArrayList<>();
        if (blocks.isEmpty() || start.compareTo(end) > 0) {
            return out;
        }
        if (!reverse) {
            for (int bi = Math.max(0, blockIndexFor(start.ms(), start.seq())); bi < blocks.size(); bi++) {
                Block block = blocks.get(bi);
                for (int slot = block.lowerBound(start.ms(), start.seq()); slot < block.count; slot++) {
                    if (block.data[slot] == null) {
                        continue;
                    }
                    if (StreamId.compare(block.ms[slot], block.seq[slot], end.ms(), end.seq()) > 0) {
                        return out;
                    }
                    out.add(decode(block, slot));
                    if (limit != 0 && out.size() >= limit) {
                        return out;
                    }
                }
            }
        } else {
            for (int bi = blockIndexFor(end.ms(), end.seq()); bi >= 0; bi--) {
                Block block = blocks.get(bi);
                for (int slot = block.upperBound(end.ms(), end.seq()) - 1; slot >= 0; slot--) {
                    if (block.data[slot] == null) {
                        continue;
                    }
                    if (StreamId.compare(block.ms[slot], block.seq[slot], start.ms(), start.seq()) < 0) {
                        return out;
                    }
                    out.add(decode(block, slot));
                    if (limit != 0 && out.size() >= limit) {
                        return out;
                    }
                }
            }
        }
        return out;
    }

    /** All entries, oldest first, decoded one at a time. */
    Iterator<StreamEntry> iterator() {
        return new Iterator<>() {
            private int bi;
            private int slot = -1;
            private StreamEntry next = advance();

            private StreamEntry advance() {
                while (bi < blocks.size()) {
                    Block block = blocks.get(bi);
                    for (slot++; slot < block.count; slot++) {
                        if (block.data[slot] != null) {
                            return decode(block, slot);
                        }
                    }
                    bi++;
                    slot = -1;
                }
                return null;
            }

            @Override
            public boolean hasNext() {
                return next != null;
            }

            @Override
            public StreamEntry next() {
                if (next == null) {
                    throw new NoSuchElementException();
                }
                StreamEntry current = next;
                next = advance();
                return current;
            }
        };
    }

    // --------------------------------------------------------------- removal

    /** @return whether there was such an entry */
    boolean delete(long ms, long seq) {
        int bi = blockIndexFor(ms, seq);
        if (bi < 0) {
            return false;
        }
        Block block = blocks.get(bi);
        int slot = block.find(ms, seq);
        if (slot < 0 || block.data[slot] == null) {
            return false;
        }
        bytes += block.remove(slot);
        size--;
        if (block.live == 0) {
            bytes -= block.footprint();
            blocks.remove(bi);
        } else if (block.sparse()) {
            bytes += block.compact();
        }
        return true;
    }

    /**
     * Removes the oldest entries until {@code maxLen} are left, but not more than {@code limit} of them
     * ({@code 0}: no limit).
     *
     * @return how many were removed
     */
    long trimToLength(long maxLen, long limit) {
        long removed = 0;
        while (size > maxLen && !blocks.isEmpty() && (limit == 0 || removed < limit)) {
            Block block = blocks.get(0);
            long excess = size - maxLen;
            long allowed = limit == 0 ? Long.MAX_VALUE : limit - removed;
            if (block.live <= excess && block.live <= allowed) {
                removed += dropFirstBlock();
            } else {
                removed += removeFromHead(block, Math.min(Math.min(excess, allowed), block.live), null);
            }
        }
        return removed;
    }

    /**
     * Removes the entries with an id smaller than {@code minId}, but not more than {@code limit} of them
     * ({@code 0}: no limit).
     *
     * @return how many were removed
     */
    long trimBefore(StreamId minId, long limit) {
        long removed = 0;
        while (!blocks.isEmpty() && (limit == 0 || removed < limit)) {
            Block block = blocks.get(0);
            int lastSlot = block.lastLive();
            boolean wholeBlock = lastSlot >= 0
                    && StreamId.compare(block.ms[lastSlot], block.seq[lastSlot], minId.ms(), minId.seq()) < 0;
            long allowed = limit == 0 ? Long.MAX_VALUE : limit - removed;
            if (wholeBlock && block.live <= allowed) {
                removed += dropFirstBlock();
                continue;
            }
            removed += removeFromHead(block, allowed, minId);
            break; // either the first entry to keep was reached, or the limit
        }
        return removed;
    }

    /** @return the number of entries in the dropped block */
    private long dropFirstBlock() {
        Block block = blocks.remove(0);
        bytes -= block.footprint() + block.dataBytes;
        size -= block.live;
        return block.live;
    }

    /** Removes up to {@code max} entries from the start of the block, stopping at the first id not below {@code before}. */
    private long removeFromHead(Block block, long max, StreamId before) {
        long removed = 0;
        for (int slot = block.firstLive(); slot >= 0 && slot < block.count && removed < max; slot++) {
            if (block.data[slot] == null) {
                continue;
            }
            if (before != null
                    && StreamId.compare(block.ms[slot], block.seq[slot], before.ms(), before.seq()) >= 0) {
                break;
            }
            bytes += block.remove(slot);
            size--;
            removed++;
        }
        if (block.live == 0) {
            bytes -= block.footprint();
            blocks.remove(block);
        } else if (block.sparse()) {
            bytes += block.compact();
        }
        return removed;
    }

    // ------------------------------------------------------------------ copy

    /** A copy that shares the (never modified) entries, for a snapshot. */
    EntryLog copy() {
        EntryLog copy = new EntryLog();
        for (Block block : blocks) {
            copy.blocks.add(block.copy());
        }
        copy.size = size;
        copy.bytes = copy.recountBytes();
        return copy;
    }

    /** The estimated size, added up from scratch; tests compare it with the incremental {@link #bytes()}. */
    long recountBytes() {
        long total = 0;
        for (Block block : blocks) {
            total += block.footprint();
            for (int slot = 0; slot < block.count; slot++) {
                if (block.data[slot] != null) {
                    total += entryCost(block.data[slot]);
                }
            }
        }
        return total;
    }

    /** The number of entries, counted slot by slot; tests compare it with {@link #size()}. */
    long recountSize() {
        long total = 0;
        for (Block block : blocks) {
            for (int slot = 0; slot < block.count; slot++) {
                if (block.data[slot] != null) {
                    total++;
                }
            }
        }
        return total;
    }

    // -------------------------------------------------------------- encoding

    private static long entryCost(byte[] packed) {
        return (ARRAY_HEADER + packed.length + 7) & ~7L;
    }

    private static int varintSize(int value) {
        int size = 1;
        while ((value >>>= 7) != 0) {
            size++;
        }
        return size;
    }

    private static int putVarint(byte[] out, int position, int value) {
        while ((value & ~0x7F) != 0) {
            out[position++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out[position++] = (byte) value;
        return position;
    }

    private static int getVarint(byte[] in, int position) {
        int value = 0;
        int shift = 0;
        while (true) {
            byte b = in[position++];
            value |= (b & 0x7F) << shift;
            if (b >= 0) {
                return value;
            }
            shift += 7;
        }
    }

    @SuppressWarnings("deprecation") // copies the low byte of each char straight into the array - exactly what is wanted
    private static int putChars(byte[] out, int position, String text) {
        text.getBytes(0, text.length(), out, position);
        return position + text.length();
    }

    private static byte[] pack(String[] master, List<String> args, int from, int pairs) {
        boolean same = master.length == pairs;
        for (int i = 0; same && i < pairs; i++) {
            same = master[i].equals(args.get(from + 2 * i));
        }
        int length = 1 + (same ? 0 : varintSize(pairs));
        for (int i = 0; i < pairs; i++) {
            String value = args.get(from + 2 * i + 1);
            length += varintSize(value.length()) + value.length();
            if (!same) {
                String name = args.get(from + 2 * i);
                length += varintSize(name.length()) + name.length();
            }
        }
        byte[] out = new byte[length];
        int position = 0;
        out[position++] = same ? SAME_NAMES : OWN_NAMES;
        if (!same) {
            position = putVarint(out, position, pairs);
        }
        for (int i = 0; i < pairs; i++) {
            if (!same) {
                String name = args.get(from + 2 * i);
                position = putVarint(out, position, name.length());
                position = putChars(out, position, name);
            }
            String value = args.get(from + 2 * i + 1);
            position = putVarint(out, position, value.length());
            position = putChars(out, position, value);
        }
        return out;
    }

    private static StreamEntry decode(Block block, int slot) {
        byte[] in = block.data[slot];
        int position = 0;
        boolean same = in[position++] == SAME_NAMES;
        int pairs = same ? block.master.length : getVarint(in, position);
        if (!same) {
            position += varintSize(pairs);
        }
        String[] fields = new String[pairs * 2];
        for (int i = 0; i < pairs; i++) {
            if (same) {
                fields[2 * i] = block.master[i];
            } else {
                int nameLength = getVarint(in, position);
                position += varintSize(nameLength);
                fields[2 * i] = new String(in, position, nameLength, StandardCharsets.ISO_8859_1);
                position += nameLength;
            }
            int valueLength = getVarint(in, position);
            position += varintSize(valueLength);
            fields[2 * i + 1] = new String(in, position, valueLength, StandardCharsets.ISO_8859_1);
            position += valueLength;
        }
        return new StreamEntry(new StreamId(block.ms[slot], block.seq[slot]), fields);
    }
}
