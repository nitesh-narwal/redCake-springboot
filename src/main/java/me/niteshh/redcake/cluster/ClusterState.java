package me.niteshh.redcake.cluster;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * What this node currently knows about the cluster, plus the <b>write fence</b>.
 *
 * <p>It is a tiny bean without dependencies, for the same reason {@code ReplicationStats} is: the command
 * pipeline ({@code ClientHandler}), {@code INFO} and the metrics must be able to read it without depending on
 * the {@link FailoverCoordinator} (which depends on the replication layer, which depends on the commands).
 * Only the coordinator writes to it; everybody else reads volatile fields.
 *
 * <h3>The fence: {@link #mayWrite()}</h3>
 * A node accepts writes only while <em>all</em> of these hold - and the check runs inside the write lock for
 * every single write, so it fails closed:
 * <ol>
 *   <li>it is the {@link Mode#LEADING leader} (it owns the leader znode),</li>
 *   <li>its ZooKeeper connection is {@link ZkState#CONNECTED healthy} (not suspended or lost),</li>
 *   <li>its leadership <em>lease</em> has not run out: the lease is renewed by a successful read of the leader
 *       znode and lasts less than the session timeout, so a primary that was paused (GC, VM freeze) or cut off
 *       stops writing <b>before</b> ZooKeeper can have handed the role to somebody else,</li>
 *   <li>no hand-over is in progress.</li>
 * </ol>
 * In standalone mode (no ZooKeeper) the fence is always open.
 */
@Component
public class ClusterState {

    public enum Mode {
        /** Cluster mode is off. */
        STANDALONE,
        /** Cluster mode is on but ZooKeeper has not been reached yet. */
        JOINING,
        /** Connected, no leader: an election is running. */
        ELECTING,
        /** Another node is the primary and this one replicates from it. */
        FOLLOWING,
        /** This node owns the leader znode. */
        LEADING;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum ZkState {
        DISCONNECTED, CONNECTED, SUSPENDED, LOST;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Hook through which {@code FAILOVER} reaches the coordinator without a bean dependency. */
    @FunctionalInterface
    public interface FailoverHandler {
        /**
         * Hands the primary role over to the replica at {@code host:port} (or to the best replica if
         * {@code host} is {@code null}).
         *
         * @throws IllegalStateException with an operator-readable reason if the hand-over is refused or fails
         */
        void failover(String host, int port, long timeoutMillis);
    }

    private final boolean managed;
    private final String nodeId;
    private final String clusterName;
    private final LongSupplier nanoClock;
    private volatile long leaseNanos;

    private volatile Mode mode;
    private volatile ZkState zkState = ZkState.DISCONNECTED;
    private volatile long epoch = -1;
    private volatile String leaderNodeId = "";
    private volatile String leaderAddress = "";
    private volatile long leaseExpiresNanos;
    private volatile boolean writesPaused;
    private volatile boolean dataTrusted;
    private volatile long dataEpoch = -1;
    private volatile String electionStatus = "";
    private volatile int members;
    private volatile long lastPromotionMillis;
    private volatile FailoverHandler failoverHandler;
    private final AtomicLong promotions = new AtomicLong();
    private final AtomicLong demotions = new AtomicLong();

    @Autowired
    public ClusterState(ClusterConfig config) {
        this(config.isEnabled(), config.leaseMillis(), System::nanoTime,
                config.isEnabled() ? config.getNodeId() : "", config.isEnabled() ? config.getClusterName() : "");
    }

    public ClusterState(boolean managed, long leaseMillis, LongSupplier nanoClock) {
        this(managed, leaseMillis, nanoClock, "", "");
    }

    private ClusterState(boolean managed, long leaseMillis, LongSupplier nanoClock, String nodeId, String clusterName) {
        this.managed = managed;
        this.nodeId = nodeId;
        this.clusterName = clusterName;
        this.nanoClock = nanoClock;
        this.leaseNanos = leaseMillis * 1_000_000L;
        this.mode = managed ? Mode.JOINING : Mode.STANDALONE;
    }

    /** @return an inert instance for components/tests that run without ZooKeeper */
    public static ClusterState standalone() {
        return new ClusterState(false, 0, System::nanoTime);
    }

    // -----------------the fence

    /** @return {@code true} if this node may execute a write right now */
    public boolean mayWrite() {
        if (!managed) {
            return true;
        }
        return mode == Mode.LEADING
                && zkState == ZkState.CONNECTED
                && !writesPaused
                && nanoClock.getAsLong() - leaseExpiresNanos < 0;
    }

    /** @return the error (with its code word) to send when {@link #mayWrite()} is {@code false} */
    public String writeRefusal() {
        return switch (mode) {
            case JOINING -> "CLUSTERDOWN this node has not joined the cluster yet (waiting for ZooKeeper)";
            case ELECTING -> "CLUSTERDOWN no primary is elected; writes are paused until a new primary is promoted";
            case FOLLOWING -> "READONLY replica does not accept writes" + primaryHint();
            case STANDALONE -> "CLUSTERDOWN writes are not available";
            case LEADING -> {
                if (writesPaused) {
                    yield "CLUSTERDOWN this primary is handing over leadership; retry shortly";
                }
                if (zkState != ZkState.CONNECTED) {
                    yield "CLUSTERDOWN this primary lost its ZooKeeper connection and is fenced; writes are paused";
                }
                yield "CLUSTERDOWN this primary could not renew its leadership lease and is fenced; writes are paused";
            }
        };
    }

    /** New replicas may attach only to a primary that is allowed to write. */
    public boolean canServeReplicas() {
        return !managed || mayWrite();
    }

    /** @return {@code true} while this node is the leader but must not write (suspended, lease over, hand-over) */
    public boolean isFenced() {
        return managed && mode == Mode.LEADING && !mayWrite();
    }

    /** @return {@code ", the primary is host:port"} when known, otherwise an empty string (error text suffix) */
    public String primaryHint() {
        String address = leaderAddress;
        return managed && !address.isEmpty() ? " (the primary is " + address + ")" : "";
    }

    public long leaseRemainingMillis() {
        long remaining = leaseExpiresNanos - nanoClock.getAsLong();
        return Math.max(0, remaining / 1_000_000L);
    }

    // ----------- readers

    public boolean isManaged() {
        return managed;
    }

    public String nodeId() {
        return nodeId;
    }

    public String clusterName() {
        return clusterName;
    }

    public Mode mode() {
        return mode;
    }

    public ZkState zkState() {
        return zkState;
    }

    /** @return the epoch this node leads or follows, {@code -1} if none */
    public long epoch() {
        return epoch;
    }

    public String leaderNodeId() {
        return leaderNodeId;
    }

    /** @return {@code host:port} of the current primary, or an empty string if unknown */
    public String leaderAddress() {
        return leaderAddress;
    }

    /**
     * @return {@code false} until the node holds data it can vouch for (it leads, or it completed a sync);
     * a node that only replayed an old append-only file must not answer reads yet
     */
    public boolean isDataTrusted() {
        return !managed || dataTrusted;
    }

    public long dataEpoch() {
        return dataEpoch;
    }

    public String electionStatus() {
        return electionStatus;
    }

    public int members() {
        return members;
    }

    public long promotions() {
        return promotions.get();
    }

    public long demotions() {
        return demotions.get();
    }

    public long lastPromotionMillis() {
        return lastPromotionMillis;
    }

    public FailoverHandler failoverHandler() {
        return failoverHandler;
    }

    // ------------ written by the coordinator

    public void setFailoverHandler(FailoverHandler handler) {
        this.failoverHandler = handler;
    }

    public void joining(long durableDataEpoch) {
        this.mode = Mode.JOINING;
        this.dataEpoch = durableDataEpoch;
        this.dataTrusted = false;
    }

    public void electing() {
        this.mode = Mode.ELECTING;
        this.leaderNodeId = "";
        this.leaderAddress = "";
        expireLease();
    }

    public void following(String nodeId, String host, int port, long leaderEpoch) {
        this.leaderNodeId = nodeId;
        this.leaderAddress = host + ":" + port;
        this.epoch = leaderEpoch;
        expireLease();
        this.writesPaused = false;
        this.mode = Mode.FOLLOWING;
    }

    /**
     * Opens the fence. {@code leaseStartNanos} is the moment the leadership was last <em>proven</em>
     * (taken before the ZooKeeper call), never "now", so the lease can only be too short.
     */
    public void leading(String nodeId, String address, long leaderEpoch, long leaseStartNanos) {
        this.leaderNodeId = nodeId;
        this.leaderAddress = address;
        this.epoch = leaderEpoch;
        this.dataEpoch = leaderEpoch;
        this.dataTrusted = true;
        this.writesPaused = false;
        this.leaseExpiresNanos = leaseStartNanos + leaseNanos;
        this.lastPromotionMillis = System.currentTimeMillis();
        this.promotions.incrementAndGet();
        this.mode = Mode.LEADING;
    }

    /**
     * Sets the lease length from the session timeout ZooKeeper actually granted (it may clamp the requested
     * one), never longer than {@code leaseMillis}: a lease longer than the real session would be unsafe.
     */
    public void leaseMillis(long leaseMillis) {
        this.leaseNanos = leaseMillis * 1_000_000L;
    }

    /** Extends the lease from the instant {@code startNanos} at which leadership was last proven. */
    public void renewLease(long startNanos) {
        this.leaseExpiresNanos = startNanos + leaseNanos;
    }

    public void demoted() {
        this.demotions.incrementAndGet();
    }

    public void zkConnected() {
        this.zkState = ZkState.CONNECTED;
    }

    /** The connection dropped: the lease is void at once (the leader has to prove itself again). */
    public void zkSuspended() {
        this.zkState = ZkState.SUSPENDED;
        expireLease();
    }

    public void zkLost() {
        this.zkState = ZkState.LOST;
        expireLease();
    }

    /** "Expired" is {@code now}, not 0: {@code nanoTime()} has an arbitrary origin and may be negative. */
    private void expireLease() {
        this.leaseExpiresNanos = nanoClock.getAsLong();
    }

    public void pauseWrites() {
        this.writesPaused = true;
    }

    public void resumeWrites() {
        this.writesPaused = false;
    }

    public void trustData() {
        this.dataTrusted = true;
    }

    public void dataEpoch(long value) {
        this.dataEpoch = value;
    }

    public void electionStatus(String status) {
        this.electionStatus = status;
    }

    public void members(int count) {
        this.members = count;
    }
}
