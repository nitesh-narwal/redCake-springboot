package me.niteshh.redcake.cluster;

import lombok.Getter;

import java.util.regex.Pattern;

/**
 * Settings of the ZooKeeper-coordinated cluster mode.
 *
 * <p>Cluster mode is <b>off</b> unless {@code --zk-connect} is given. When it is on, ZooKeeper decides which
 * node is the primary; {@code --replicaof} and {@code REPLICAOF} are refused (see
 * {@code docs/adr/0005-zookeeper-failover.md}).
 *
 * <p>Timing relations the rest of the code relies on (T = {@link #sessionTimeoutMs}):
 * <ul>
 *   <li>ZooKeeper deletes a dead node's ephemeral znodes about T after its last heartbeat.</li>
 *   <li>A primary may accept writes for at most {@link #leaseMillis()} = 2T/3 after the last time it proved it
 *       still owns the leader znode, so it always stops <em>before</em> a successor can be elected.</li>
 * </ul>
 */
@Getter
public final class ClusterConfig {

    public static final String DEFAULT_CLUSTER_NAME = "redcake";
    public static final int DEFAULT_SESSION_TIMEOUT_MS = 10_000;
    public static final int DEFAULT_CONNECTION_TIMEOUT_MS = 5_000;
    public static final int DEFAULT_ELECTION_DELAY_MS = 1_000;
    /** Same convention as Redis {@code replica-priority}: lower wins, 0 = never promote. */
    public static final int DEFAULT_REPLICA_PRIORITY = 100;

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,100}");
    /** A znode name: no '/', and "." / ".." are not allowed by ZooKeeper (checked separately). */
    private static final Pattern NODE_ID = Pattern.compile("[A-Za-z0-9._:@\\[\\]-]{1,200}");

    /** {@code host:port[,host:port...][/chroot]}; {@code null} = cluster mode off. */
    private final String connectString;
    private final String clusterName;
    private final String nodeId;
    /** Address other nodes and clients use to reach this node's data port. */
    private final String advertiseHost;
    private final int advertisePort;
    private final int replicaPriority;
    private final int sessionTimeoutMs;
    private final int connectionTimeoutMs;
    /** Quiet period after the leader disappears, and the per-rank spacing of promotion attempts. */
    private final int electionDelayMs;
    /** {@code user:password} for ZooKeeper digest authentication, or {@code null}. */
    private final String zkAuth;

    public ClusterConfig(
            String connectString,
            String clusterName,
            String nodeId,
            String advertiseHost,
            int advertisePort,
            int replicaPriority,
            int sessionTimeoutMs,
            int connectionTimeoutMs,
            int electionDelayMs,
            String zkAuth
    ) {
        if (connectString != null && connectString.isBlank()) {
            throw new IllegalArgumentException("--zk-connect must not be empty");
        }
        if (connectString != null) {
            if (clusterName == null || !NAME.matcher(clusterName).matches()) {
                throw new IllegalArgumentException(
                        "--cluster-name may only contain letters, digits, '.', '_' and '-': " + clusterName);
            }
            if (advertiseHost == null || advertiseHost.isBlank()) {
                throw new IllegalArgumentException("--advertise-host must not be empty");
            }
            if (advertisePort < 1 || advertisePort > 65535) {
                throw new IllegalArgumentException("--advertise-port must be between 1 and 65535");
            }
            if (nodeId == null || !NODE_ID.matcher(nodeId).matches() || nodeId.equals(".") || nodeId.equals("..")) {
                throw new IllegalArgumentException(
                        "--node-id may only contain letters, digits and . _ : @ [ ] - (max 200 characters): " + nodeId);
            }
            if (replicaPriority < 0) {
                throw new IllegalArgumentException("--replica-priority must not be negative");
            }
            if (sessionTimeoutMs < 200) {
                throw new IllegalArgumentException("--zk-session-timeout-ms must be at least 200");
            }
            if (connectionTimeoutMs < 100) {
                throw new IllegalArgumentException("--zk-connection-timeout-ms must be at least 100");
            }
            if (electionDelayMs < 0) {
                throw new IllegalArgumentException("--cluster-election-delay-ms must not be negative");
            }
            if (zkAuth != null && zkAuth.indexOf(':') < 1) {
                throw new IllegalArgumentException("ZooKeeper credentials must have the form user:password");
            }
        }
        this.connectString = connectString;
        this.clusterName = clusterName;
        this.nodeId = nodeId;
        this.advertiseHost = advertiseHost;
        this.advertisePort = advertisePort;
        this.replicaPriority = replicaPriority;
        this.sessionTimeoutMs = sessionTimeoutMs;
        this.connectionTimeoutMs = connectionTimeoutMs;
        this.electionDelayMs = electionDelayMs;
        this.zkAuth = zkAuth;
    }

    /** @return a config with cluster mode turned off */
    public static ClusterConfig disabled() {
        return new ClusterConfig(null, null, null, null, 0, DEFAULT_REPLICA_PRIORITY,
                DEFAULT_SESSION_TIMEOUT_MS, DEFAULT_CONNECTION_TIMEOUT_MS, DEFAULT_ELECTION_DELAY_MS, null);
    }

    public boolean isEnabled() {
        return connectString != null;
    }

    /** How long a primary may keep accepting writes after its last proof of leadership. */
    public long leaseMillis() {
        return sessionTimeoutMs * 2L / 3;
    }

    /**
     * How often the primary re-proves its leadership. Several attempts fit into one lease, even if ZooKeeper
     * grants a shorter session than requested; one cheap read every half second is nothing for ZooKeeper.
     */
    public long leaseRenewMillis() {
        return Math.max(50, Math.min(500, sessionTimeoutMs / 6));
    }

    /** Cadence of the coordinator's reconcile loop (watch events trigger it earlier). */
    public long tickMillis() {
        return Math.max(50, Math.min(500, sessionTimeoutMs / 10));
    }

    /**
     * Spacing between promotion attempts of consecutive ranks. At least two ticks, so rank 1 can never fire in
     * the same tick as rank 0 and win by luck of timing (the claim is atomic either way; this only keeps the
     * best-informed node first).
     */
    public long staggerMillis() {
        return Math.max(electionDelayMs, 2 * tickMillis());
    }
}
