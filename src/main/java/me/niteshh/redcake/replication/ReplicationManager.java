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
            log.info("Primary configured at {}:{}", config.getPrimaryHost(), config.getPrimaryPort());
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
            if (replid.equals(stats.replid()) && current.canServe(offset)) {
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
     * Starts a new replication history (new replid, empty backlog at the
     * current offset) so replicas of the old primary cannot confuse the two.
     */
    public void promoteToPrimary() {
        log.info("Promoting to primary");
        replicaSyncManager.stop();
        primaryWriteLock.lock();
        try {
            config.becomePrimary();
            stats.startNewHistory();
            stats.setMasterLinkUp(false);
            stats.setLoading(false);
            backlog = new ReplicationBacklog(config.getBacklogBytes(), stats.offset());
            stats.setBacklog(backlog);
        } finally {
            primaryWriteLock.unlock();
        }
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
