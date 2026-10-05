package me.niteshh.redcake.replication.primary;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Registry of the replicas attached to this primary (one primary supports
 * any number of replicas). All operations are thread-safe.
 */
@Slf4j
@Component
public class ReplicaManager {

    private final Map<String, ReplicaConnection> replicaConnections = new ConcurrentHashMap<>();

    /**
     * Adds a replica and arranges for it to be removed automatically if its
     * writer thread hits an I/O error.
     */
    public void register(ReplicaConnection replica) {
        replica.setFailureListener(failed -> unregister(failed.getReplicaId()));
        replicaConnections.put(replica.getReplicaId(), replica);
        log.info("Replica registered: {}", replica.getReplicaId());
    }

    /**
     * Queues {@code command} on every replica. Never blocks on network I/O
     * (see {@link ReplicaConnection#send}); replicas that cannot accept it
     * are reported to {@code onFailure}.
     */
    public void broadcast(byte[] command, Consumer<ReplicaConnection> onFailure) {
        for (ReplicaConnection replica : replicaConnections.values()) {
            try {
                replica.send(command);
            } catch (Exception e) {
                log.warn("Dropping replica {}: {}", replica.getReplicaId(), e.getMessage());
                onFailure.accept(replica);
            }
        }
    }

    /** Removes and closes a replica; harmless if it is already gone. */
    public void unregister(String replicaId) {
        ReplicaConnection removed = replicaConnections.remove(replicaId);
        if (removed != null) {
            removed.close();
            log.info("Replica unregistered: {}", replicaId);
        }
    }

    /** @return a snapshot copy of the current replicas */
    public List<ReplicaConnection> getReplicas() {
        return Collections.unmodifiableList(new ArrayList<>(replicaConnections.values()));
    }

    public int replicaCount() {
        return replicaConnections.size();
    }

    /** Closes every replica (server shutdown). */
    public void closeAll() {
        for (ReplicaConnection replica : replicaConnections.values()) {
            replica.close();
        }
        replicaConnections.clear();
    }
}
