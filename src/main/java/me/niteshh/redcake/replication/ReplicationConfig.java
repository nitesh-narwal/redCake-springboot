package me.niteshh.redcake.replication;

/**
 * Replication role of this node and, for a replica, where its primary lives.
 *
 * <p>Set from {@code --replicaof <host> <port>} at startup and changeable at
 * runtime with {@code REPLICAOF}, so the fields are volatile and updated
 * together under {@code synchronized} (a reader never sees a new role with an
 * old host).
 */
public class ReplicationConfig {

    /** Default size of the partial-resync backlog: 1 MiB. */
    public static final int DEFAULT_BACKLOG_BYTES = 1024 * 1024;

    private volatile ReplicationRole role;
    private volatile String primaryHost;
    private volatile int primaryPort;
    private final int backlogBytes;

    public ReplicationConfig(ReplicationRole role, String primaryHost, int primaryPort) {
        this(role, primaryHost, primaryPort, DEFAULT_BACKLOG_BYTES);
    }

    /**
     * @param backlogBytes how many bytes of the write stream a primary keeps so
     *                     a briefly disconnected replica can resume without a full sync
     */
    public ReplicationConfig(ReplicationRole role, String primaryHost, int primaryPort, int backlogBytes) {
        if (backlogBytes < 1024) {
            throw new IllegalArgumentException("repl-backlog-size must be at least 1024 bytes");
        }
        this.role = role;
        this.primaryHost = primaryHost;
        this.primaryPort = primaryPort;
        this.backlogBytes = backlogBytes;
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
}
