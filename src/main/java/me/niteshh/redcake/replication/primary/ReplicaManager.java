package me.niteshh.redcake.replication.primary;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Manages the primary replica connections.
 * This allows one primary to support N replicas.
 */
@Component
public class ReplicaManager {

    private final Map<String, ReplicaConnection> replicaConnections = new ConcurrentHashMap<>();

    public void register( ReplicaConnection replica) {
        try{
            replicaConnections.put(replica.getReplicaId(), replica);
            System.out.println("Replica registered: " + replica.getReplicaId());
        } catch (Exception e) {
            System.err.println("Error registering replica: " + replica.getReplicaId());
            e.printStackTrace();
        }
    }

    public void broadcast(
            byte[] command,
            Consumer<ReplicaConnection> onFailure
    ) {
        for (ReplicaConnection replica : replicaConnections.values()) {
            try {
                replica.send(command);
            } catch (Exception e) {
                onFailure.accept(replica);
            }
        }
    }

    public void unregister(String replicaId) {
        try{
            ReplicaConnection removed = replicaConnections.remove(replicaId);
            if (removed != null) {
                removed.close();
                System.out.println("Replica unregistered: " + replicaId);
            } else {
                System.out.println("No replica found with ID: " + replicaId);
            }
        } catch (Exception e) {
            System.err.println("Error unregistering replica: " + replicaId);
            e.printStackTrace();
        }
    }

    public ReplicaConnection getReplica(String replicaId) {
        try {
            return replicaConnections.get(replicaId);
        } catch (Exception e) {
            System.err.println("Error getting replica: " + replicaId);
            e.printStackTrace();
            return null;
        }
    }

    public List<ReplicaConnection> getReplicas() {
        return Collections.unmodifiableList(
                new ArrayList<>(replicaConnections.values())
        );
    }

    public int replicaCount() {
        return replicaConnections.size();
    }

    public boolean contains(String replicaId) {
        return replicaConnections.containsKey(replicaId);
    }

    public void closeAll() {
        for (ReplicaConnection replica : replicaConnections.values()) {
            replica.close();
        }
        replicaConnections.clear();
    }
}
