package me.niteshh.redcake.stream;

/**
 * The id of a stream entry: {@code <milliseconds>-<sequence>}, two <em>unsigned</em> 64-bit numbers ordered by the
 * milliseconds first. Java has no unsigned long, so the two parts are stored as signed longs and compared with
 * {@link Long#compareUnsigned}; {@link #toString} prints them unsigned, so {@code 18446744073709551615-0} survives.
 *
 * <p>The ids a client writes are parsed by {@link #parse}: only decimal digits, at most 20 per part, no sign and no
 * blanks (Redis also takes a few odd spellings such as {@code +5}; refusing them loses nothing).
 */
public record StreamId(long ms, long seq) implements Comparable<StreamId> {

    /** {@code 0-0}: smaller than every entry id, and the "nothing yet" id of a stream or a group. */
    public static final StreamId ZERO = new StreamId(0, 0);

    /** The largest id there can be. */
    public static final StreamId MAX = new StreamId(-1L, -1L);

    /** Longest accepted text of an id, as in Redis. */
    private static final int MAX_TEXT = 127;

    /** What {@link #parse} found: the id, and whether the sequence part was written ({@code false} for {@code 5-*}). */
    public record Parsed(StreamId id, boolean seqGiven) {
    }

    @Override
    public int compareTo(StreamId other) {
        int byMs = Long.compareUnsigned(ms, other.ms);
        return byMs != 0 ? byMs : Long.compareUnsigned(seq, other.seq);
    }

    public static int compare(long msA, long seqA, long msB, long seqB) {
        int byMs = Long.compareUnsigned(msA, msB);
        return byMs != 0 ? byMs : Long.compareUnsigned(seqA, seqB);
    }

    public boolean isZero() {
        return ms == 0 && seq == 0;
    }

    /** @return the id right after this one, or {@code null} if this is the largest one */
    public StreamId next() {
        if (seq != -1L) {
            return new StreamId(ms, seq + 1);
        }
        return ms != -1L ? new StreamId(ms + 1, 0) : null;
    }

    /** @return the id right before this one, or {@code null} if this is {@code 0-0} */
    public StreamId previous() {
        if (seq != 0) {
            return new StreamId(ms, seq - 1);
        }
        return ms != 0 ? new StreamId(ms - 1, -1L) : null;
    }

    @Override
    public String toString() {
        return Long.toUnsignedString(ms) + "-" + Long.toUnsignedString(seq);
    }

    /**
     * Parses {@code ms}, {@code ms-seq} or - when {@code allowAutoSequence} - {@code ms-*}.
     *
     * @param missingSeq the sequence part when the text has only the milliseconds ({@code 0} for a start or an exact
     *                   id, {@code -1L} - the largest - for the end of a range)
     * @return the id, or {@code null} if the text is not an id
     */
    public static Parsed parse(String text, long missingSeq, boolean allowAutoSequence) {
        int length = text.length();
        if (length == 0 || length > MAX_TEXT) {
            return null;
        }
        int dash = text.indexOf('-');
        if (dash < 0) {
            Long onlyMs = unsigned(text, 0, length);
            return onlyMs == null ? null : new Parsed(new StreamId(onlyMs, missingSeq), true);
        }
        Long ms = unsigned(text, 0, dash);
        if (ms == null) {
            return null;
        }
        if (allowAutoSequence && length == dash + 2 && text.charAt(dash + 1) == '*') {
            return new Parsed(new StreamId(ms, 0), false);
        }
        Long seq = unsigned(text, dash + 1, length);
        return seq == null ? null : new Parsed(new StreamId(ms, seq), true);
    }

    /** An id as the commands that name one entry take it: {@code ms} or {@code ms-seq}; {@code null} if it is neither. */
    public static StreamId parseExact(String text) {
        Parsed parsed = parse(text, 0, false);
        return parsed == null ? null : parsed.id();
    }

    /** Decimal digits only, 1 to 20 of them, value below 2^64; {@code null} otherwise. */
    private static Long unsigned(String text, int from, int to) {
        if (from >= to || to - from > 20) {
            return null;
        }
        long value = 0;
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
            if (Long.compareUnsigned(value, 1844674407370955161L) > 0) {
                return null; // times ten would overflow
            }
            long times = value * 10;
            long sum = times + (c - '0');
            if (Long.compareUnsigned(sum, times) < 0) {
                return null; // the addition wrapped around
            }
            value = sum;
        }
        return value;
    }
}
