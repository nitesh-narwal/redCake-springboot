package me.niteshh.redcake.replication;

import lombok.Getter;
@Getter
public class ReplicationConfig {

    /**
     * Role of this RedCake instance.
     *
     * Default:
     * PRIMARY
     */
    private final ReplicationRole role;

    /**
     * Host of the primary.
     *
     * Used when this node is a REPLICA.
     */
    private final String primaryHost;

    /**
     * Port of the primary.
     *
     * Used when this node is a REPLICA.
     */
    private final int primaryPort;

    public ReplicationConfig(ReplicationRole role, String primaryHost, int primaryPort) {
        this.role = role;
        this.primaryHost = primaryHost;
        this.primaryPort = primaryPort;
    }
}
