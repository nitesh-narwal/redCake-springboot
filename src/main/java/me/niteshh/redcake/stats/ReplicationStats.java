package me.niteshh.redcake.stats;

import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Replication identity and progress shared between the replication layer
 * (writer) and {@code INFO}/{@code ROLE} (readers).
 *
 * <p>It lives in its own tiny bean so {@code InfoCommand} can report real
 * values without depending on {@code ReplicationManager}, which would create
 * a dependency cycle (ReplicationManager -> ReplicaSyncManager -> CommandHandler
 * -> InfoCommand).
 *
 * <ul>
 *   <li>{@code replid} - 40-hex id of a replication history. A primary creates
 *       its own; a replica <em>adopts</em> its primary's, so both report the
 *       same id and a reconnecting replica can prove it follows the same history.</li>
 *   <li>{@code replid2}/{@code secondReplidOffset} - after a promotion the new primary remembers the
 *       history it was following, valid up to the offset it had reached. Replicas of the old primary can then
 *       continue from the new one with a <em>partial</em> resync instead of copying the whole dataset
 *       (Redis "PSYNC2").</li>
 *   <li>{@code offset} - bytes of write stream produced (primary) or applied
 *       (replica). Equal offsets mean "fully caught up".</li>
 *   <li>{@code synced} - replica only: it holds a complete, consistent copy,
 *       so on reconnect it may ask for a partial resync instead of a full one.</li>
 *   <li>{@code loading} - replica only: a full snapshot is being applied, so reads are
 *       refused with {@code -LOADING} instead of returning a half-loaded dataset.</li>
 *   <li>{@code masterLinkUp} - replica only: currently connected <em>and</em> synced.</li>
 * </ul>
 */
@Component
public class ReplicationStats {

    private static final SecureRandom RANDOM = new SecureRandom();

    private volatile String replid = newReplid();
    private volatile String replid2 = "";
    private volatile long secondReplidOffset = -1;
    private final AtomicLong offset = new AtomicLong();
    private volatile boolean masterLinkUp;
    private volatile boolean synced;
    private volatile me.niteshh.redcake.replication.ReplicationBacklog backlog;
    /**
     * @return {@code true} while a replica is loading a full snapshot (its data is incomplete)
     */
    @Getter
    @Setter
    private volatile boolean loading;

    public String replid() {
        return replid;
    }

    public long offset() {
        return offset.get();
    }

    /** Adds {@code bytes} to the replication offset and returns the new offset. */
    public long advance(long bytes) {
        return offset.addAndGet(bytes);
    }

    /** The previous history id a promoted node still honours, or an empty string. */
    public String replid2() {
        return replid2;
    }

    /** Replicas of {@link #replid2()} may continue only if their offset is at most this one; -1 if none. */
    public long secondReplidOffset() {
        return secondReplidOffset;
    }

    /**
     * @return {@code true} if a replica that applied {@code offset} bytes of history {@code id} follows a
     * stream this node's history is a continuation of, so it may resume without a full copy
     */
    public boolean continuesHistory(String id, long offset) {
        return id.equals(replid)
                || (!replid2.isEmpty() && id.equals(replid2) && offset <= secondReplidOffset);
    }

    /** Replica: take over the primary's history id and position (start of a sync). */
    public synchronized void adopt(String primaryReplid, long primaryOffset) {
        if (!primaryReplid.equals(this.replid)) {
            this.replid2 = ""; // that was a promotion record of a past primary stint; it no longer applies
            this.secondReplidOffset = -1;
        }
        this.replid = primaryReplid;
        this.offset.set(primaryOffset);
    }

    /**
     * Primary promotion: start a new history so a stale node cannot mistake it for the previous one, but keep
     * the old id (valid up to the current offset) so replicas of the old primary can still resume from here.
     * The offset itself is not reset: the new stream continues the old one.
     */
    public synchronized void shiftReplicationId() {
        this.replid2 = this.replid;
        this.secondReplidOffset = this.offset.get();
        this.replid = newReplid();
    }

    public boolean isMasterLinkUp() {
        return masterLinkUp;
    }

    public void setMasterLinkUp(boolean up) {
        this.masterLinkUp = up;
    }

    public boolean isSynced() {
        return synced;
    }

    public void setSynced(boolean synced) {
        this.synced = synced;
    }

    /** @return the primary's partial-resync backlog, or {@code null} before it exists (for {@code INFO}) */
    public me.niteshh.redcake.replication.ReplicationBacklog backlog() {
        return backlog;
    }

    /** Published by {@code ReplicationManager} whenever it creates a (new) backlog. */
    public void setBacklog(me.niteshh.redcake.replication.ReplicationBacklog backlog) {
        this.backlog = backlog;
    }

    private static String newReplid() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        StringBuilder id = new StringBuilder(40);
        for (byte value : bytes) {
            id.append(String.format("%02x", value & 0xff));
        }
        return id.toString();
    }
}
