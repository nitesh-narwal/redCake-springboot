package me.niteshh.redcake.replication;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import me.niteshh.redcake.replication.primary.ReplicaConnection;
import me.niteshh.redcake.replication.primary.ReplicaManager;
import me.niteshh.redcake.replication.replica.ReplicaSyncManager;
import me.niteshh.redcake.stats.ReplicationStats;
import me.niteshh.redcake.store.KeyValueStore;
import me.niteshh.redcake.store.SnapshotEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Orchestrates replication for both roles and can switch between them at runtime.
 *
 * <h3>Primary</h3>
 * <ul>
 *   <li>{@link #lockPrimaryWrites}/{@link #unlockPrimaryWrites} - the single
 *       write-ordering lock. While held, a command is executed, logged and
 *       <em>queued</em> for replicas, so every replica receives writes in
 *       exactly the primary's execution order. Replica queues are in-memory
 *       only, so holding the lock never waits on the network.</li>
 *   <li>{@link #registerReplica} - sync of a (re)connecting replica. Inside one
 *       critical section it decides between a <b>partial</b> resync (the
 *       replica proved it follows our history and the bytes it misses are still
 *       in the {@link ReplicationBacklog}) and a <b>full</b> resync (a
 *       point-in-time copy of the dataset), and registers the replica so every
 *       later write is queued for it. No write is ever both in the snapshot and
 *       in the queue.</li>
 *   <li>{@link #replicate} - appends a write to the backlog, advances the
 *       offset and fans it out.</li>
 *   <li>{@link #waitForReplicas} - the engine of {@code WAIT}.</li>
 *   <li>A heartbeat {@code PING} every 10 s keeps idle links alive and lets
 *       replicas detect a dead primary via their read timeout.</li>
 * </ul>
 *
 * <h3>Replica</h3>
 * Delegates to {@link ReplicaSyncManager}. {@link #becomeReplicaOf} and
 * {@link #promoteToPrimary} implement {@code REPLICAOF host port} and
 * {@code REPLICAOF NO ONE}.
 *
 * <h3>Failover (ZooKeeper-coordinated mode)</h3>
 * The failover coordinator drives the same machinery through {@link #promoteToPrimary} (an elected replica
 * starts a new history but keeps the old id valid, so the other replicas continue with a <em>partial</em>
 * resync), {@link #followPrimary} (point at the elected primary, keeping the history so a former primary can
 * also resume) and {@link #stepDown} (a primary that lost its leadership becomes a read-only node without a
 * primary until it is told who the new one is). Because every replica mirrors the stream it applies into its
 * own {@link ReplicationBacklog}, any of them can serve the others after a promotion.
 */
@Slf4j
@Component
public class ReplicationManager {

    /** How often an idle primary pings its replicas. */
    private static final long HEARTBEAT_SECONDS = 10;
    /** Snapshot bytes are flushed to the socket every N commands. */
    private static final int SNAPSHOT_FLUSH_INTERVAL = 256;
    /** How long shutdown waits for replica queues to drain. */
    private static final long SHUTDOWN_DRAIN_MILLIS = 2_000;

    private final ReplicationConfig config;
    private final ReplicaManager replicaManager;
    private final ReplicaSyncManager replicaSyncManager;
    private final KeyValueStore store;
    private final ReplicationStats stats;
    private final AtomicLong replicaIds = new AtomicLong();
    private final ReentrantLock primaryWriteLock = new ReentrantLock();

    private volatile ReplicationBacklog backlog;
    private ScheduledExecutorService heartbeat;

    @Autowired
    public ReplicationManager(
            ReplicationConfig config,
            ReplicaManager replicaManager,
            ReplicaSyncManager replicaSyncManager,
            KeyValueStore store,
            ReplicationStats stats
    ) {
        this.config = config;
        this.replicaManager = replicaManager;
        this.replicaSyncManager = replicaSyncManager;
        this.store = store;
        this.stats = stats;
        this.backlog = new ReplicationBacklog(config.getBacklogBytes(), stats.offset());
        stats.setBacklog(this.backlog); // so INFO can report it
    }

    /** Convenience constructor with private statistics; used by unit tests. */
    public ReplicationManager(
            ReplicationConfig config,
            ReplicaManager replicaManager,
            ReplicaSyncManager replicaSyncManager,
            KeyValueStore store
    ) {
        this(config, replicaManager, replicaSyncManager, store, new ReplicationStats());
    }

    @PostConstruct
    public void initialize() {
        log.info("Replication role: {}", config.getRole());
        heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "RedCake-ReplicationHeartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeat.scheduleWithFixedDelay(
                this::sendHeartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS
        );

        if (isReplica()) {
            if (config.getPrimaryHost() == null) {
                log.info("Replica without a primary: waiting for the cluster coordinator to assign one");
            } else {
                log.info("Primary configured at {}:{}", config.getPrimaryHost(), config.getPrimaryPort());
            }
            replicaSyncManager.start();
        }
    }

    /** Sends {@code PING} to replicas (inside the write lock to keep stream order and offset exact). */
    private void sendHeartbeat() {
        if (!isPrimary() || replicaManager.replicaCount() == 0) {
            return;
        }
        primaryWriteLock.lock();
        try {
            replicate(List.of("PING"));
        } finally {
            primaryWriteLock.unlock();
        }
    }

    public void lockPrimaryWrites() {
        primaryWriteLock.lock();
    }

    public void unlockPrimaryWrites() {
        primaryWriteLock.unlock();
    }

    // ------------------------------------------------------------ syncing

    /**
     * Synchronizes a replica that sent {@code PSYNC <replid> <offset>}
     * ({@code ? -1} on its first connection).
     *
     * <ul>
     *   <li>Same history and the missing bytes are in the backlog: reply
     *       {@code REPLICAHELLO CONTINUE}, then send only those bytes.</li>
     *   <li>Otherwise: {@code REPLICAHELLO FULLRESYNC}, then one command set per
     *       key (the dataset copy is taken under the write lock, so it is
     *       point-in-time).</li>
     * </ul>
     * Either way the stream ends with {@code REPLICAHELLO SYNCED}, after which
     * the writes queued meanwhile are released in order.
     *
     * <p>Trade-off of the full path: entry <em>copies</em> are held in memory
     * briefly in exchange for a consistent snapshot.
     *
     * @param listeningPort the port the replica announced (shown in {@code INFO}); 0 if unknown
     * @return the registered replica connection
     * @throws IOException if streaming fails (the replica is unregistered)
     */
    public ReplicaConnection registerReplica(
            Socket socket,
            String replid,
            long offset,
            int listeningPort
    ) throws IOException {
        List<SnapshotEntry> entries = new ArrayList<>();
        byte[] missed = null;
        long syncOffset;
        ReplicaConnection replica;

        primaryWriteLock.lock();
        try {
            ReplicationBacklog current = backlog;
            if (stats.continuesHistory(replid, offset) && current.canServe(offset)) {
                missed = current.slice(offset);
            } else {
                store.forEachSnapshot(entries::add);
            }
            syncOffset = missed != null ? offset : stats.offset();

            String id = socket.getRemoteSocketAddress() + "-" + replicaIds.incrementAndGet();
            replica = new ReplicaConnection(id, socket);
            replica.setListeningPort(listeningPort);
            replicaManager.register(replica);
        } finally {
            primaryWriteLock.unlock();
        }

        try {
            replica.writeSnapshotCommand(RespCommandCodec.encode(List.of(
                    "REPLICAHELLO",
                    missed != null ? "CONTINUE" : "FULLRESYNC",
                    stats.replid(),
                    Long.toString(syncOffset))));

            if (missed != null) {
                replica.writeSnapshotCommand(missed);
                log.info("Partial resync for replica {}: {} bytes", replica.getReplicaId(), missed.length);
            } else {
                int sent = 1;
                for (SnapshotEntry entry : entries) {
                    for (List<String> command : entry.toCommands()) {
                        replica.writeSnapshotCommand(RespCommandCodec.encode(command));
                        if (++sent % SNAPSHOT_FLUSH_INTERVAL == 0) {
                            replica.flushSnapshot();
                        }
                    }
                }
                log.info("Full resync for replica {}: {} keys", replica.getReplicaId(), entries.size());
            }

            replica.writeSnapshotCommand(RespCommandCodec.encode(List.of("REPLICAHELLO", "SYNCED")));
            replica.finishSnapshot();
            return replica;
        } catch (IOException e) {
            replicaManager.unregister(replica.getReplicaId());
            throw e;
        }
    }

    /** Drops a replica whose client connection ended. */
    public void unregisterReplica(ReplicaConnection replica) {
        replicaManager.unregister(replica.getReplicaId());
    }

    // --------------------------------------------------------- the stream

    /**
     * Records a successfully executed write command in the backlog, advances
     * the replication offset and queues it for every replica. Must be called
     * with the write lock held.
     */
    public void replicate(List<String> command) {
        if (!isPrimary()) {
            return;
        }
        byte[] encoded = RespCommandCodec.encode(command);
        backlog.append(encoded);
        stats.advance(encoded.length);
        replicaManager.broadcast(
                encoded,
                replica -> replicaManager.unregister(replica.getReplicaId())
        );
    }

    /**
     * Blocks until {@code count} replicas have applied everything written so
     * far, or the timeout passes. Asks replicas to acknowledge immediately
     * (a {@code REPLCONF GETACK} marker in the stream) instead of waiting for
     * their once-per-second ack.
     *
     * @param timeoutMillis 0 waits (practically) forever, like Redis
     * @return how many replicas acknowledged the current offset
     */
    public int waitForReplicas(int count, long timeoutMillis) throws InterruptedException {
        long target;
        primaryWriteLock.lock();
        try {
            replicate(List.of("REPLCONF", "GETACK", "*"));
            target = stats.offset();
        } finally {
            primaryWriteLock.unlock();
        }

        long deadline = timeoutMillis == 0
                ? Long.MAX_VALUE
                : System.currentTimeMillis() + timeoutMillis;
        while (true) {
            int acknowledged = (int) replicaManager.getReplicas().stream()
                    .filter(r -> r.getAckOffset() >= target)
                    .count();
            if (acknowledged >= count || System.currentTimeMillis() >= deadline) {
                return acknowledged;
            }
            Thread.sleep(5);
        }
    }

    // --------------------------------------------------- role switching

    /**
     * {@code REPLICAOF host port}: turn this node into a replica. Replicas
     * attached to it are disconnected (chaining is unsupported) and the node
     * will full-sync from the new primary, discarding its own data.
     */
    public void becomeReplicaOf(String host, int port) {
        log.info("Becoming a replica of {}:{}", host, port);
        primaryWriteLock.lock();
        try {
            config.becomeReplicaOf(host, port);
            replicaManager.closeAll();
        } finally {
            primaryWriteLock.unlock();
        }
        stats.setSynced(false);
        replicaSyncManager.restart();
    }

    /**
     * {@code REPLICAOF NO ONE}: stop following the primary and accept writes.
     * Starts a new replication history (new replid) so replicas of the old primary cannot confuse the two -
     * but the old id stays valid up to the current offset ({@code replid2}), so those replicas can resume from
     * this node with a partial resync as long as the bytes they miss are still in the backlog. The backlog
     * kept while this node was a replica holds exactly those bytes; it is only replaced if it does not line up
     * with the offset (a node that never mirrored a stream).
     */
    public void promoteToPrimary() {
        log.info("Promoting to primary");
        replicaSyncManager.stop(); // returns only after the stream applier has quiesced
        primaryWriteLock.lock();
        try {
            config.becomePrimary();
            stats.shiftReplicationId();
            stats.setMasterLinkUp(false);
            stats.setLoading(false);
            if (backlog.endOffset() != stats.offset()) {
                backlog = new ReplicationBacklog(config.getBacklogBytes(), stats.offset());
                stats.setBacklog(backlog);
            }
        } finally {
            primaryWriteLock.unlock();
        }
    }

    /**
     * Failover: become a replica of the newly elected primary at {@code host:port}. Unlike
     * {@link #becomeReplicaOf} this keeps the replication history: a node that was a primary (or a replica
     * of the previous one) asks to continue at its offset, and the new primary decides - by comparing replid
     * and offset - between a partial resync and a full one that discards writes nobody else has.
     */
    public void followPrimary(String host, int port) {
        log.info("Following primary {}:{}", host, port);
        primaryWriteLock.lock();
        try {
            boolean wasPrimary = isPrimary();
            config.becomeReplicaOf(host, port);
            replicaManager.closeAll();
            if (wasPrimary) {
                stats.setSynced(true); // its dataset is a complete copy as of (replid, offset)
            }
            stats.setMasterLinkUp(false);
        } finally {
            primaryWriteLock.unlock();
        }
        replicaSyncManager.restart();
    }

    /**
     * A primary that lost its leadership: stop accepting writes at once, disconnect the replicas (they will
     * be pointed at the new primary) and wait, read-only and without a primary, to be told who it is.
     * The history is kept so this node can resume, rather than fully resync, if its data is a prefix of the
     * new primary's.
     */
    public void stepDown() {
        log.info("Stepping down from primary");
        primaryWriteLock.lock();
        try {
            config.becomeDetached();
            replicaManager.closeAll();
            stats.setSynced(true);
            stats.setMasterLinkUp(false);
        } finally {
            primaryWriteLock.unlock();
        }
        replicaSyncManager.stop();
    }

    /**
     * @return how many attached replicas acknowledged within {@code maxLagSeconds}
     * ({@code min-replicas-to-write} counts these)
     */
    public int goodReplicas(int maxLagSeconds) {
        int good = 0;
        for (ReplicaConnection replica : replicaManager.getReplicas()) {
            if (replica.getAckOffset() >= 0 && replica.getLagSeconds() <= maxLagSeconds) {
                good++;
            }
        }
        return good;
    }

    /** {@code min-replicas-to-write}: {@code true} if the configured number of good replicas is attached (or none is required). */
    public boolean hasEnoughReplicasToWrite() {
        int required = config.getMinReplicasToWrite();
        return required == 0 || goodReplicas(config.getMinReplicasMaxLagSeconds()) >= required;
    }

    /** @return the number of attached replicas (for the graceful hand-over, which waits for all of them) */
    public int replicaCount() {
        return replicaManager.replicaCount();
    }

    // ------------------------------------------------------------ accessors

    public ReplicationRole getRole() {
        return config.getRole();
    }

    public boolean isPrimary() {
        return config.getRole() == ReplicationRole.PRIMARY;
    }

    public boolean isReplica() {
        return config.getRole() == ReplicationRole.REPLICA;
    }

    public ReplicationStats getStats() {
        return stats;
    }

    public KeyValueStore getStore() {
        return store;
    }

    /** Stops heartbeats, lets replica queues drain briefly, then closes everything. */
    @PreDestroy
    public void shutdown() {
        if (heartbeat != null) {
            heartbeat.shutdownNow();
        }
        if (isPrimary()) {
            for (ReplicaConnection replica : replicaManager.getReplicas()) {
                try {
                    replica.awaitIdle(SHUTDOWN_DRAIN_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            replicaManager.closeAll();
        } else {
            replicaSyncManager.stop();
        }
        log.info("Replication manager stopped");
    }
}
