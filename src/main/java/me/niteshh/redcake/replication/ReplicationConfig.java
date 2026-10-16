package me.niteshh.redcake.replication;

/**
 * Replication role of this node and, for a replica, where its primary lives.
 *
 * <p>Set from {@code --replicaof <host> <port>} at startup and changeable at
 * runtime with {@code REPLICAOF}, so the fields are volatile and updated
 * together under {@code synchronized} (a reader never sees a new role with an
 * old host).
 *
 * <p>In <em>coordinated</em> mode ({@code --zk-connect}) ZooKeeper assigns the roles instead: the node starts
 * as a replica <b>without</b> a primary ({@link #becomeDetached()}, host {@code null}) and the failover
 * coordinator promotes it or points it at the elected primary.
 */
public class ReplicationConfig {

    /** Default size of the partial-resync backlog: 1 MiB. */
    public static final int DEFAULT_BACKLOG_BYTES = 1024 * 1024;

    /** Redis default: a replica that has not acknowledged for this long is not "good". */
    public static final int DEFAULT_MIN_REPLICAS_MAX_LAG_SECONDS = 10;

    private volatile ReplicationRole role;
    private volatile String primaryHost;
    private volatile int primaryPort;
    private final int backlogBytes;
    private final boolean coordinated;
    private volatile int minReplicasToWrite;
    private volatile int minReplicasMaxLagSeconds = DEFAULT_MIN_REPLICAS_MAX_LAG_SECONDS;

    public ReplicationConfig(ReplicationRole role, String primaryHost, int primaryPort) {
        this(role, primaryHost, primaryPort, DEFAULT_BACKLOG_BYTES);
    }

    /**
     * @param backlogBytes how many bytes of the write stream a primary keeps so
     *                     a briefly disconnected replica can resume without a full sync
     */
    public ReplicationConfig(ReplicationRole role, String primaryHost, int primaryPort, int backlogBytes) {
        this(role, primaryHost, primaryPort, backlogBytes, false);
    }

    /**
     * @param coordinated {@code true} if ZooKeeper assigns the role ({@code --zk-connect}); then
     *                    {@code REPLICAOF} is refused and the AOF is replayed even though the node starts
     *                    as a replica (it may be elected primary from its own data)
     */
    public ReplicationConfig(
            ReplicationRole role, String primaryHost, int primaryPort, int backlogBytes, boolean coordinated
    ) {
        if (backlogBytes < 1024) {
            throw new IllegalArgumentException("repl-backlog-size must be at least 1024 bytes");
        }
        this.role = role;
        this.primaryHost = primaryHost;
        this.primaryPort = primaryPort;
        this.backlogBytes = backlogBytes;
        this.coordinated = coordinated;
    }

    /** @return PRIMARY or REPLICA */
    public ReplicationRole getRole() {
        return role;
    }

    /** @return the primary's host (meaningful for a REPLICA) */
    public String getPrimaryHost() {
        return primaryHost;
    }

    /** @return the primary's port (meaningful for a REPLICA) */
    public int getPrimaryPort() {
        return primaryPort;
    }

    public int getBacklogBytes() {
        return backlogBytes;
    }

    /** @return {@code true} if the failover coordinator (ZooKeeper) decides this node's role */
    public boolean isCoordinated() {
        return coordinated;
    }

    /** {@code min-replicas-to-write}: a primary refuses writes with fewer good replicas; 0 = off. */
    public int getMinReplicasToWrite() {
        return minReplicasToWrite;
    }

    public void setMinReplicasToWrite(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("min-replicas-to-write must not be negative");
        }
        this.minReplicasToWrite = count;
    }

    /** {@code min-replicas-max-lag}: seconds since a replica's last acknowledgement that still count as "good". */
    public int getMinReplicasMaxLagSeconds() {
        return minReplicasMaxLagSeconds;
    }

    public void setMinReplicasMaxLagSeconds(int seconds) {
        if (seconds < 1) {
            throw new IllegalArgumentException("min-replicas-max-lag must be at least 1");
        }
        this.minReplicasMaxLagSeconds = seconds;
    }

    /** Switches this node to a replica of {@code host:port}. */
    public synchronized void becomeReplicaOf(String host, int port) {
        this.primaryHost = host;
        this.primaryPort = port;
        this.role = ReplicationRole.REPLICA;
    }

    /** Switches this node to a primary (promotion). */
    public synchronized void becomePrimary() {
        this.role = ReplicationRole.PRIMARY;
        this.primaryHost = null;
    }

    /**
     * Read-only with no primary assigned (yet): a coordinated node at startup, or a former primary that lost
     * its leadership and has not been told who the new one is.
     */
    public synchronized void becomeDetached() {
        this.role = ReplicationRole.REPLICA;
        this.primaryHost = null;
    }
}
