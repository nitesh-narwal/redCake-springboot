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

    /** Replica: take over the primary's history id and position (start of a sync). */
    public void adopt(String primaryReplid, long primaryOffset) {
        this.replid = primaryReplid;
        this.offset.set(primaryOffset);
    }

    /** Primary promotion: begin a new history so old replicas cannot mistake it for the previous one. */
    public void startNewHistory() {
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
