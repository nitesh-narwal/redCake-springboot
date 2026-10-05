package me.niteshh.redcake.replication;

/** Whether this node accepts writes (PRIMARY) or mirrors another node read-only (REPLICA). */
public enum ReplicationRole {
    PRIMARY,
    REPLICA
}
