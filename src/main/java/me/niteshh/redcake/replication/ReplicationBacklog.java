package me.niteshh.redcake.replication;

/**
 * Fixed-size ring buffer holding the most recent bytes of the replication
 * stream, addressed by absolute stream offset.
 *
 * <p>A replica that disconnects for a moment remembers how many stream bytes
 * it had applied (its offset). On reconnect the primary checks
 * {@link #canServe}: if those bytes are still in the ring it sends only the
 * missing part ({@link #slice}) instead of a full snapshot. Offsets only ever
 * grow, so a replica that fell further behind than the ring size is detected
 * and gets a full resync.
 */
public final class ReplicationBacklog {

    private final byte[] ring;
    /** Offset of the oldest byte still held. */
    private long startOffset;
    /** Offset one past the newest byte (also: total bytes ever appended + initial offset). */
    private long endOffset;

    /**
     * @param capacity    ring size in bytes
     * @param startOffset the stream offset this backlog begins at
     */
    public ReplicationBacklog(int capacity, long startOffset) {
        this.ring = new byte[capacity];
        this.startOffset = startOffset;
        this.endOffset = startOffset;
    }

    /** Appends bytes at the end, overwriting the oldest when full. */
    public synchronized void append(byte[] data) {
        int from = 0;
        if (data.length > ring.length) {
            // Only the last `capacity` bytes can matter; account for the skipped ones.
            from = data.length - ring.length;
            endOffset += from;
        }
        for (int i = from; i < data.length; i++) {
            ring[(int) (endOffset % ring.length)] = data[i];
            endOffset++;
        }
        startOffset = Math.max(startOffset, endOffset - ring.length);
    }

    /** @return {@code true} if the stream from {@code offset} to the end is still held */
    public synchronized boolean canServe(long offset) {
        return offset >= startOffset && offset <= endOffset;
    }

    /**
     * @return a copy of the bytes from {@code offset} to the end
     * @throws IllegalArgumentException if {@link #canServe} would be false
     */
    public synchronized byte[] slice(long offset) {
        if (!canServe(offset)) {
            throw new IllegalArgumentException("Offset " + offset + " is outside the backlog");
        }
        byte[] out = new byte[(int) (endOffset - offset)];
        for (int i = 0; i < out.length; i++) {
            out[i] = ring[(int) ((offset + i) % ring.length)];
        }
        return out;
    }

    public synchronized long startOffset() {
        return startOffset;
    }

    public synchronized long endOffset() {
        return endOffset;
    }

    /** Empties the backlog and restarts it at {@code offset} (new replication history). */
    public synchronized void reset(long offset) {
        startOffset = offset;
        endOffset = offset;
    }
}
