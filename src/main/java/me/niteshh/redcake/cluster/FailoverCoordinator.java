package me.niteshh.redcake.cluster;

import lombok.extern.slf4j.Slf4j;
import me.niteshh.redcake.cluster.ZooKeeperService.ConnectionEvent;
import me.niteshh.redcake.cluster.ZooKeeperService.EpochView;
import me.niteshh.redcake.cluster.ZooKeeperService.LeaderView;
import me.niteshh.redcake.config.PersistenceConfig;
import me.niteshh.redcake.persistence.AppendOnlyLog;
import me.niteshh.redcake.replication.ReplicationConfig;
import me.niteshh.redcake.replication.ReplicationManager;
import me.niteshh.redcake.replication.ReplicationRole;
import me.niteshh.redcake.stats.ReplicationStats;
import me.niteshh.redcake.store.KeyValueStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * Keeps this node's replication role in line with ZooKeeper: elects a new primary when the old one is gone,
 * points replicas at it, and makes sure a primary that lost its mandate stops writing.
 * The reasoning behind every rule is in {@code docs/adr/0005-zookeeper-failover.md}.
 *
 * <h3>One level-triggered loop</h3>
 * Everything happens in {@link #reconcile()}, which runs on a single thread, every tick and whenever a
 * watched znode or the connection changes. Each run <em>reads the current ZooKeeper state and derives what to
 * do from it</em>; it never depends on having seen every event, so lost, duplicated or reordered
 * notifications are harmless. The cases:
 * <ul>
 *   <li><b>A leader exists and it is another node</b> - follow it ({@link ReplicationManager#followPrimary}).
 *       If this node thought it was the leader, it steps down first.</li>
 *   <li><b>A leader exists and it is this node</b> - keep leading and renew the lease.
 *       (A leader znode carrying this node's id but another session is the ghost of a previous run and is
 *       removed.)</li>
 *   <li><b>No leader</b> - election: wait a settle period so every node publishes its final offset, rank the
 *       eligible nodes ({@link ElectionPolicy}), and let rank <i>r</i> try to claim the leader znode
 *       <i>r</i> x delay later. The claim is an atomic ZooKeeper transaction, so exactly one node wins even
 *       if two try at once; the staggering only makes it the best-informed one.</li>
 * </ul>
 *
 * <h3>Never two writers</h3>
 * A leader may write only while {@link ClusterState#mayWrite()} holds: connected, and its lease - renewed by a
 * successful read of its own leader znode, shorter than the session timeout - is unexpired. A leader cut off
 * from ZooKeeper (or frozen by a long pause) therefore stops writing <em>before</em> its znode can disappear
 * and a successor can be elected. When it comes back it finds the znode gone or owned by somebody else and
 * becomes a replica of the new leader.
 *
 * <h3>Planned hand-over</h3>
 * Shutdown and {@code FAILOVER} do not wait for a timeout: the primary stops accepting writes, waits for its
 * replicas to acknowledge everything, writes a {@link FailoverHint} naming the successor and releases the
 * leader znode. Nothing is lost and the new primary is up within a tick.
 *
 * <p>It is a {@link SmartLifecycle} so that it starts after the server is listening and the append-only file
 * is replayed, and stops - handing over - <em>before</em> the replication layer shuts down.
 */
@Slf4j
@Component
public class FailoverCoordinator implements SmartLifecycle {

    /** Offsets and key counts change constantly; publish those at most this often. */
    private static final long PUBLISH_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);
    /** A promotion that failed is not retried for this long. */
    private static final long PROMOTION_BACKOFF_NANOS = TimeUnit.SECONDS.toNanos(5);
    /** How long shutdown waits for replicas to acknowledge before it releases leadership anyway. */
    private static final long SHUTDOWN_DRAIN_MILLIS = 3_000;
    /** A hand-over hint that nobody acts on is ignored after this long. */
    private static final long HINT_VALID_MILLIS = 10_000;
    /** Below this a dropped connection can cost the session just by the time the ZooKeeper client needs to reconnect. */
    private static final int SHORT_SESSION_MILLIS = 5_000;

    private final ClusterConfig config;
    private final ClusterState state;
    private final ZooKeeperService zk;
    private final ReplicationManager replication;
    private final ReplicationConfig replicationConfig;
    private final ReplicationStats stats;
    private final AppendOnlyLog appendOnlyLog;
    private final PersistenceConfig persistence;
    private final KeyValueStore store;
    private final LongSupplier nanoClock;

    private final String incarnation = randomId();

    private volatile boolean running;
    private volatile boolean stopping;
    private volatile boolean suspended;
    private volatile long suspendedSinceNanos;
    private volatile long sessionTimeoutMs;

    private ScheduledExecutorService worker;
    private ScheduledExecutorService leaseWorker;

    // Touched only by the worker thread. nanoTime() has an arbitrary origin and may be negative, so "unset" is
    // never encoded as the number 0.
    private long knownSession = -1;
    private boolean electing;
    private long electionSinceNanos;
    private long promotionBackoffUntilNanos;
    private NodeRecord lastPublished;
    private long lastPublishNanos;
    private String lastElectionSummary = "";
    private String lastFailure = "";
    private Path epochFile;

    @Autowired
    public FailoverCoordinator(
            ClusterConfig config,
            ClusterState state,
            ZooKeeperService zk,
            ReplicationManager replication,
            ReplicationConfig replicationConfig,
            ReplicationStats stats,
            AppendOnlyLog appendOnlyLog,
            PersistenceConfig persistence,
            KeyValueStore store
    ) {
        this(config, state, zk, replication, replicationConfig, stats, appendOnlyLog, persistence, store,
                System::nanoTime);
    }

    FailoverCoordinator(
            ClusterConfig config,
            ClusterState state,
            ZooKeeperService zk,
            ReplicationManager replication,
            ReplicationConfig replicationConfig,
            ReplicationStats stats,
            AppendOnlyLog appendOnlyLog,
            PersistenceConfig persistence,
            KeyValueStore store,
            LongSupplier nanoClock
    ) {
        this.config = config;
        this.state = state;
        this.zk = zk;
        this.replication = replication;
        this.replicationConfig = replicationConfig;
        this.stats = stats;
        this.appendOnlyLog = appendOnlyLog;
        this.persistence = persistence;
        this.store = store;
        this.nanoClock = nanoClock;
        this.sessionTimeoutMs = config.getSessionTimeoutMs();
    }

    // ----------- lifecycle

    @Override
    public synchronized void start() {
        if (!config.isEnabled() || running) {
            return;
        }
        epochFile = persistence.isEnabled() ? ClusterEpochFile.forAof(persistence.getFile()) : null;
        long durableEpoch = epochFile == null ? -1 : ClusterEpochFile.read(epochFile);
        state.joining(durableEpoch);
        state.leaseMillis(config.leaseMillis());
        promotionBackoffUntilNanos = nanoClock.getAsLong(); // "now": no backoff active
        state.setFailoverHandler(this::failover);

        running = true;
        stopping = false;
        worker = Executors.newSingleThreadScheduledExecutor(runnable -> daemon(runnable, "RedCake-Cluster"));
        leaseWorker = Executors.newSingleThreadScheduledExecutor(runnable -> daemon(runnable, "RedCake-ClusterLease"));

        log.info("Cluster mode: node {} ({}), cluster '{}', ZooKeeper {}, priority {}, session timeout {} ms, "
                        + "durable data epoch {}",
                config.getNodeId(), config.getAdvertiseHost() + ":" + config.getAdvertisePort(),
                config.getClusterName(), config.getConnectString(), config.getReplicaPriority(),
                config.getSessionTimeoutMs(), durableEpoch);

        if (config.getZkAuth() == null) {
            log.warn("No ZooKeeper credentials (REDCAKE_ZK_AUTH / --zk-auth-file): anyone who can reach ZooKeeper can "
                    + "read the election state and choose this cluster's primary. Use digest credentials and "
                    + "--tls-replication on anything but a private network.");
        }
        if (config.getSessionTimeoutMs() < SHORT_SESSION_MILLIS) {
            log.warn("ZooKeeper session timeout of {} ms is short: after a dropped connection the ZooKeeper client alone "
                    + "can need up to about 2 s to reconnect (more with a single server in --zk-connect), and a session "
                    + "lost that way costs an election. Use 5000 ms or more and list every ensemble member.",
                    config.getSessionTimeoutMs());
        }
        zk.start(this::onConnectionEvent, this::requestReconcile);
        worker.scheduleWithFixedDelay(this::tick, 0, config.tickMillis(), TimeUnit.MILLISECONDS);
        leaseWorker.scheduleWithFixedDelay(this::renewLease,
                config.leaseRenewMillis(), config.leaseRenewMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Hands the primary role over (if this node has it), then leaves the cluster. Running before the
     * replication layer is destroyed is what lets the replicas drain and the next primary start without a gap.
     */
    @Override
    public void stop() {
        ScheduledExecutorService stoppingWorker;
        synchronized (this) {
            if (!running) {
                return;
            }
            stopping = true;
            stoppingWorker = worker;
        }
        try {
            stoppingWorker.submit(this::handOverOnShutdown).get(SHUTDOWN_DRAIN_MILLIS + 5_000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | RejectedExecutionException e) {
            log.warn("Hand-over on shutdown did not complete: {}", e.toString());
        }
        synchronized (this) {
            running = false;
            worker.shutdownNow();
            leaseWorker.shutdownNow();
            zk.close(); // removes this node's ephemeral znodes at once
        }
        log.info("Cluster coordinator stopped");
    }

    /**
     * Stops the way a crash would: no hand-over and no tidy goodbye to ZooKeeper. If the connection is
     * unreachable the session simply times out on the server, which is the situation the failover logic must
     * survive. For tests; production code uses {@link #stop()}.
     */
    synchronized void abort() {
        stopping = true;
        running = false;
        if (worker != null) {
            worker.shutdownNow();
        }
        if (leaseWorker != null) {
            leaseWorker.shutdownNow();
        }
        zk.close();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    // ---------- events

    /** Curator thread: only flips volatile state, so a suspended connection fences writes immediately. */
    private void onConnectionEvent(ConnectionEvent event) {
        switch (event) {
            case CONNECTED, RECONNECTED -> {
                suspended = false;
                state.zkConnected();
                requestReconcile();
            }
            case SUSPENDED -> {
                if (!suspended) {
                    suspendedSinceNanos = nanoClock.getAsLong();
                    suspended = true;
                }
                state.zkSuspended();
            }
            case LOST -> {
                if (!suspended) {
                    suspendedSinceNanos = nanoClock.getAsLong();
                    suspended = true;
                }
                state.zkLost();
                requestReconcile();
            }
        }
    }

    private void requestReconcile() {
        ScheduledExecutorService current = worker;
        if (running && current != null) {
            try {
                current.execute(this::tick);
            } catch (RejectedExecutionException shuttingDown) {
                // the coordinator is stopping
            }
        }
    }

    private void tick() {
        if (!running || stopping) {
            return;
        }
        try {
            reconcile();
            lastFailure = "";
        } catch (Exception e) {
            // Usually ZooKeeper being unreachable; the next tick tries again. Say it once, not every tick.
            String summary = e.getClass().getSimpleName() + ": " + e.getMessage();
            if (!summary.equals(lastFailure)) {
                log.warn("Cluster reconcile failed ({}); will retry", summary);
                lastFailure = summary;
            } else {
                log.debug("Cluster reconcile still failing: {}", summary);
            }
        } catch (Error fatal) {
            // A scheduled task that throws is never run again; a silently dead loop would leave this node
            // deaf to every later failover. Report it and keep going.
            log.error("Cluster reconcile hit a fatal error", fatal);
        }
    }

    // --------- the loop

    private void reconcile() throws Exception {
        // A primary that has been cut off for a whole session timeout must assume it has been replaced.
        if (state.mode() == ClusterState.Mode.LEADING && suspended
                && nanoClock.getAsLong() - suspendedSinceNanos > TimeUnit.MILLISECONDS.toNanos(sessionTimeoutMs)) {
            demote("no contact with ZooKeeper for longer than the session timeout (" + sessionTimeoutMs + " ms)");
        }
        if (!zk.isConnected()) {
            return; // nothing else can be decided without ZooKeeper
        }

        long session = zk.sessionId();
        if (session != knownSession) {
            onNewSession(session);
        }
        zk.ensureLayout();
        if (lastPublished == null) {
            publishState(true); // make this node visible before it takes part in any election
        }

        long readStartedAt = nanoClock.getAsLong();
        Optional<LeaderView> leader = zk.readLeader(true);
        if (leader.isPresent()) {
            electing = false;
            lastElectionSummary = "";
            handleLeader(leader.get(), readStartedAt);
        } else {
            handleNoLeader();
        }

        if (!state.isDataTrusted() && stats.isSynced() && stats.isMasterLinkUp() && !stats.isLoading()) {
            state.trustData();
        }
        publishState(false);
    }

    private void onNewSession(long session) throws Exception {
        long negotiated = zk.negotiatedSessionTimeoutMs();
        sessionTimeoutMs = negotiated > 0 ? negotiated : config.getSessionTimeoutMs();
        // ZooKeeper may clamp the requested timeout; the lease must follow the real one.
        state.leaseMillis(Math.min(config.leaseMillis(), sessionTimeoutMs * 2 / 3));
        log.info("ZooKeeper session 0x{} established (timeout {} ms)", Long.toHexString(session), sessionTimeoutMs);

        if (knownSession != -1 && state.mode() == ClusterState.Mode.LEADING) {
            demote("the ZooKeeper session was replaced, so the leader znode is gone"); // belt and braces: LOST usually arrives first
        }
        knownSession = session;
        lastPublished = null; // the ephemeral member record died with the old session
        electing = false;
    }

    private void handleLeader(LeaderView view, long readStartedAt) throws Exception {
        LeaderRecord record = view.record();
        boolean mine = record.nodeId().equals(config.getNodeId());

        if (mine && view.ownerSession() == knownSession) {
            if (state.mode() == ClusterState.Mode.LEADING && state.epoch() == record.epoch()) {
                state.renewLease(readStartedAt);
            } else {
                promote(record, readStartedAt); // claimed earlier but the promotion did not finish
            }
        } else if (mine) {
            // Created by an earlier run of this very node; its session has not expired yet. We know that run
            // is dead (we are the only holder of this node id), so do not make everybody wait for the timeout.
            log.warn("Removing the stale leader node left by a previous run of this node (epoch {})", record.epoch());
            zk.releaseLeader(view.version());
        } else {
            follow(record);
        }
    }

    private void follow(LeaderRecord record) {
        if (state.mode() == ClusterState.Mode.LEADING) {
            demote("node " + record.nodeId() + " holds the leader znode (epoch " + record.epoch() + ")");
        }
        boolean sameAddress = replicationConfig.getRole() == ReplicationRole.REPLICA
                && record.host().equals(replicationConfig.getPrimaryHost())
                && record.port() == replicationConfig.getPrimaryPort();
        // A new epoch at the same address is a restarted primary; the sync loop may be deep in its backoff
        // by now, so start it afresh instead of waiting for the next retry.
        boolean newEpochWhileDown = state.epoch() != record.epoch() && !stats.isMasterLinkUp();
        if (!sameAddress || newEpochWhileDown) {
            log.info("Following {} at {} (epoch {})", record.nodeId(), record.address(), record.epoch());
            appendOnlyLog.deactivate(); // a replica's state comes from the primary
            replication.followPrimary(record.host(), record.port());
        }
        state.following(record.nodeId(), record.host(), record.port(), record.epoch());
    }

    private void handleNoLeader() throws Exception {
        if (state.mode() == ClusterState.Mode.LEADING) {
            demote("the leader znode is gone from ZooKeeper");
        }
        if (!electing) {
            electing = true;
            electionSinceNanos = nanoClock.getAsLong();
            state.electing();
            log.info("No primary in the cluster: election starts (cluster epoch so far: {})", zk.readEpoch().value());
            publishState(true); // everybody must see our final offset before ranking
        }
        runElection();
    }

    // ---------- election

    private void runElection() throws Exception {
        long now = nanoClock.getAsLong();
        if (now - promotionBackoffUntilNanos < 0) {
            return;
        }

        EpochView epoch = zk.readEpoch();
        // A hand-over hint counts only for the election right after the epoch it was written in, and only briefly.
        Optional<FailoverHint> hint = zk.readFailoverHint()
                .filter(h -> h.fromEpoch() == epoch.value() && !h.isExpired(System.currentTimeMillis()));
        String preferred = hint.filter(FailoverHint::hasTarget).map(FailoverHint::target).orElse(null);

        NodeRecord self = selfRecord();
        List<NodeRecord> members = zk.readMembers();
        members.removeIf(node -> node.nodeId().equals(self.nodeId()));
        members.add(self);

        ElectionPolicy.Ranking ranking = ElectionPolicy.rank(members, epoch.value(), preferred);
        state.members(members.size());
        state.electionStatus(ranking.summary());
        if (!ranking.summary().equals(lastElectionSummary)) {
            lastElectionSummary = ranking.summary();
            log.info("Election: {}", lastElectionSummary);
        }

        int rank = ranking.rankOf(self.nodeId());
        if (rank < 0) {
            return; // not eligible; somebody else will be
        }

        long settle = hint.isPresent() ? 0 : TimeUnit.MILLISECONDS.toNanos(config.getElectionDelayMs());
        long due = electionSinceNanos + settle + rank * TimeUnit.MILLISECONDS.toNanos(config.staggerMillis());
        if (now - due < 0) {
            return; // not our turn yet
        }

        long claimStartedAt = nanoClock.getAsLong();
        LeaderRecord record = new LeaderRecord(self.nodeId(), config.getAdvertiseHost(), config.getAdvertisePort(),
                epoch.value() + 1, incarnation, System.currentTimeMillis());
        if (!zk.claimLeadership(record, epoch)) {
            log.info("Another node became the leader first (this node tried epoch {})", record.epoch());
            return; // the leader watch fires and the next run follows the winner
        }
        log.info("Won the election: epoch {}, {} (rank {}, offset {})",
                record.epoch(), config.getNodeId(), rank, self.offset());
        promote(record, claimStartedAt);
    }

    // ---------- role transitions

    /**
     * Turns this node into the primary after it owns the leader znode. Order matters: the history and the log
     * are prepared <em>before</em> the fence opens, so the first write already lands in a consistent AOF.
     */
    private void promote(LeaderRecord record, long leaseStartedAt) {
        try {
            replication.promoteToPrimary();
            replication.lockPrimaryWrites();
            try {
                appendOnlyLog.activateFromSnapshot();
                if (epochFile != null) {
                    ClusterEpochFile.write(epochFile, record.epoch()); // before the first write is accepted
                }
            } finally {
                replication.unlockPrimaryWrites();
            }
            electing = false;
            state.leading(record.nodeId(), record.address(), record.epoch(), leaseStartedAt);
            log.info("Promoted to primary: epoch {}, replication id {}, offset {}",
                    record.epoch(), stats.replid(), stats.offset());
            clearHandOverHint();
            publishState(true);
        } catch (Exception e) {
            log.error("Promotion to primary failed; releasing the leader znode", e);
            promotionBackoffUntilNanos = nanoClock.getAsLong() + PROMOTION_BACKOFF_NANOS;
            abandonLeadership();
        }
    }

    /** The hand-over it announced has happened; do not let the hint shorten a later, unrelated election. */
    private void clearHandOverHint() {
        try {
            zk.clearFailoverHint();
        } catch (Exception e) {
            log.debug("Could not clear the failover hint: {}", e.toString()); // it expires on its own
        }
    }

    /** Gives up the role without waiting for anything: fence first, then tidy up the replication layer. */
    private void demote(String reason) {
        log.warn("Giving up the primary role: {}", reason);
        state.electing();   // from here on every write is refused
        state.demoted();
        replication.stepDown();
        appendOnlyLog.deactivate();
    }

    private void abandonLeadership() {
        try {
            zk.readLeader(false)
                    .filter(view -> view.ownerSession() == knownSession)
                    .ifPresent(view -> {
                        try {
                            zk.releaseLeader(view.version());
                        } catch (Exception e) {
                            log.warn("Could not release the leader znode: {}", e.toString());
                        }
                    });
        } catch (Exception e) {
            log.warn("Could not release the leader znode: {}", e.toString());
        }
        demote("promotion failed");
        electing = false;
    }

    // -------- lease & publish

    /** Lease thread: proves the leadership again and again; a leader that cannot do so stops writing. */
    private void renewLease() {
        try {
            if (!running || stopping || state.mode() != ClusterState.Mode.LEADING || !zk.isConnected()) {
                return;
            }
            long startedAt = nanoClock.getAsLong(); // before the call: the lease can only be too short
            Optional<LeaderView> view = zk.readLeader(false);
            boolean stillMine = view.isPresent()
                    && view.get().record().nodeId().equals(config.getNodeId())
                    && view.get().record().epoch() == state.epoch()
                    && view.get().ownerSession() == zk.sessionId();
            if (stillMine) {
                state.renewLease(startedAt);
            } else {
                requestReconcile(); // the worker thread demotes
            }
        } catch (Exception e) {
            log.debug("Lease renewal failed: {}", e.toString());
        } catch (Error fatal) {
            log.error("Lease renewal hit a fatal error", fatal); // the lease then runs out and the primary fences itself
        }
    }

    private void publishState(boolean force) throws Exception {
        NodeRecord self = selfRecord();
        long now = nanoClock.getAsLong();
        if (lastPublished == null) {
            zk.registerMember(self);
        } else {
            boolean changed = !self.sameStateAs(lastPublished);
            boolean progressed = (self.offset() != lastPublished.offset() || self.keys() != lastPublished.keys())
                    && now - lastPublishNanos >= PUBLISH_INTERVAL_NANOS;
            if (!force && !changed && !progressed) {
                return;
            }
            zk.updateMember(self);
        }
        lastPublished = self;
        lastPublishNanos = now;
    }

    private NodeRecord selfRecord() {
        ClusterState.Mode mode = state.mode();
        NodeRecord.Role role = switch (mode) {
            case LEADING -> NodeRecord.Role.LEADER;
            case FOLLOWING -> NodeRecord.Role.FOLLOWER;
            default -> NodeRecord.Role.CANDIDATE;
        };

        // Which leader's data does this node hold in full? A follower inherits the epoch of the leader it has
        // completed a sync with; while a full resync is running its data is incomplete.
        long dataEpoch;
        if (mode == ClusterState.Mode.LEADING) {
            dataEpoch = state.epoch();
        } else if (stats.isLoading()) {
            dataEpoch = -1;
        } else if (stats.isSynced() && stats.isMasterLinkUp() && state.epoch() >= 0) {
            dataEpoch = state.epoch();
        } else {
            dataEpoch = state.dataEpoch();
        }
        state.dataEpoch(dataEpoch);

        return new NodeRecord(config.getNodeId(), config.getAdvertiseHost(), config.getAdvertisePort(),
                config.getReplicaPriority(), role, stats.replid(), stats.offset(), dataEpoch,
                persistence.isEnabled(), !state.isDataTrusted(), stats.isLoading(), store.size(), state.epoch(),
                incarnation, System.currentTimeMillis());
    }

    // ------- hand-over

    /** Shutdown path (worker thread): a primary hands over before it disappears. */
    private void handOverOnShutdown() {
        try {
            if (state.mode() == ClusterState.Mode.LEADING && zk.isConnected()) {
                handOver(null, SHUTDOWN_DRAIN_MILLIS);
            }
        } catch (Exception e) {
            log.warn("Graceful hand-over failed ({}); ZooKeeper will release the leader znode when the session ends",
                    e.toString());
        }
    }

    /** {@code FAILOVER}: runs on the worker thread so it never interleaves with a reconcile. */
    private void failover(String host, int port, long timeoutMillis) {
        if (!running || stopping) {
            throw new IllegalStateException("the cluster coordinator is not running");
        }
        Future<?> result;
        try {
            result = worker.submit(() -> {
                try {
                    doFailover(host, port, timeoutMillis);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException("unexpected error: " + e.getMessage(), e);
                }
            });
        } catch (RejectedExecutionException e) {
            throw new IllegalStateException("the cluster coordinator is not running");
        }
        try {
            result.get(timeoutMillis + 10_000, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof IllegalStateException failure
                    ? failure
                    : new IllegalStateException("unexpected error: " + e.getCause(), e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("failover did not finish in time; check INFO cluster");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        }
    }

    private void doFailover(String host, int port, long timeoutMillis) throws Exception {
        if (state.mode() != ClusterState.Mode.LEADING) {
            String where = state.leaderAddress().isEmpty() ? "" : " (the primary is " + state.leaderAddress() + ")";
            throw new IllegalStateException("this node is not the primary" + where + "; send FAILOVER there");
        }
        if (!state.mayWrite()) {
            throw new IllegalStateException("this primary is fenced and cannot hand over right now");
        }

        List<NodeRecord> members = zk.readMembers();
        members.removeIf(node -> node.nodeId().equals(config.getNodeId()));
        EpochView epoch = zk.readEpoch();

        String target;
        if (host != null) {
            NodeRecord chosen = members.stream()
                    .filter(node -> node.host().equals(host) && node.port() == port)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "no cluster member is advertised as " + host + ":" + port));
            if (chosen.role() != NodeRecord.Role.FOLLOWER) {
                throw new IllegalStateException(host + ":" + port + " is not a replica of this primary");
            }
            String reason = ElectionPolicy.exclusionReason(chosen, epoch.value(), true);
            if (reason != null) {
                throw new IllegalStateException(host + ":" + port + " cannot take over: " + reason);
            }
            target = chosen.nodeId();
        } else {
            target = ElectionPolicy.rank(
                            members.stream().filter(node -> node.role() == NodeRecord.Role.FOLLOWER).toList(),
                            epoch.value(), null)
                    .best()
                    .map(NodeRecord::nodeId)
                    .orElseThrow(() -> new IllegalStateException("no eligible replica to take over"));
        }

        log.info("Planned failover to {}", target);
        handOver(target, timeoutMillis);
    }

    /**
     * Stops writes, waits until the replicas have everything, names the successor and releases the leader
     * znode. If the replicas do not catch up in time and a specific successor was requested, nothing changes.
     */
    private void handOver(String targetNodeId, long timeoutMillis) throws Exception {
        state.pauseWrites(); // the fence closes: no new write is accepted from here on
        boolean completed = false;
        try {
            int attached = replication.replicaCount();
            int acknowledged = attached == 0 ? 0 : replication.waitForReplicas(attached, timeoutMillis);
            long finalOffset = stats.offset();
            if (acknowledged < attached && targetNodeId != null) {
                throw new IllegalStateException("replicas did not catch up within " + timeoutMillis
                        + " ms (" + acknowledged + " of " + attached + " acknowledged); nothing was changed");
            }
            if (targetNodeId != null) {
                awaitPublishedOffset(targetNodeId, finalOffset, timeoutMillis);
            }

            zk.writeFailoverHint(new FailoverHint(
                    targetNodeId == null ? "" : targetNodeId, state.epoch(),
                    System.currentTimeMillis() + HINT_VALID_MILLIS));
            Optional<LeaderView> view = zk.readLeader(false);
            if (view.isPresent() && view.get().ownerSession() == knownSession) {
                zk.releaseLeader(view.get().version());
            }
            completed = true;
        } finally {
            if (!completed) {
                state.resumeWrites();
            }
        }
        demote("handed over" + (targetNodeId == null ? "" : " to " + targetNodeId));
        electing = false;
    }

    /** Waits until the successor's own published offset shows it applied everything the primary produced. */
    private void awaitPublishedOffset(String nodeId, long offset, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (true) {
            Optional<NodeRecord> node = zk.readMembers().stream()
                    .filter(member -> member.nodeId().equals(nodeId)).findFirst();
            if (node.isPresent() && node.get().replid().equals(stats.replid()) && node.get().offset() >= offset) {
                return;
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new IllegalStateException(nodeId + " has not confirmed offset " + offset
                        + " within " + timeoutMillis + " ms; nothing was changed");
            }
            Thread.sleep(50);
        }
    }

    private static String randomId() {
        byte[] bytes = new byte[6];
        new SecureRandom().nextBytes(bytes);
        StringBuilder id = new StringBuilder();
        for (byte value : bytes) {
            id.append(String.format("%02x", value & 0xff));
        }
        return id.toString();
    }
}
