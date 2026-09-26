package me.niteshh.redcake.replication;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import me.niteshh.redcake.replication.primary.ReplicaManager;
import me.niteshh.redcake.replication.primary.ReplicaConnection;
import me.niteshh.redcake.replication.replica.PrimaryConnection;
import me.niteshh.redcake.replication.replica.ReplicaSyncManager;
import me.niteshh.redcake.store.KeyValueStore;
import me.niteshh.redcake.store.SnapshotEntry;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

@Getter
@Component
public class ReplicationManager {

    private final ReplicationConfig config;

    private final ReplicaManager replicaManager;

    private final PrimaryConnection primaryConnection;
    private final ReplicaSyncManager replicaSyncManager;
    private final KeyValueStore store;
    private final AtomicLong replicaIds = new AtomicLong();
    private final ReentrantLock primaryWriteLock = new ReentrantLock();

    public ReplicationManager(
            ReplicationConfig config,
            ReplicaManager replicaManager,
            PrimaryConnection primaryConnection,
            ReplicaSyncManager replicaSyncManager,
            KeyValueStore store
    ) {
        this.config = config;
        this.replicaManager = replicaManager;
        this.primaryConnection = primaryConnection;
        this.replicaSyncManager = replicaSyncManager;
        this.store = store;
    }

    @PostConstruct
    public void initialize() {
        System.out.println("Replication role: " + config.getRole());
        if (isPrimary()) {
            initializePrimary();
        } else {
            initializeReplica();
        }
    }

    private void initializePrimary() {
        System.out.println("RedCake started as PRIMARY");
    }

    private void initializeReplica() {
        System.out.println("RedCake started as REPLICA");

        System.out.println(
                "Primary configured at: " + config.getPrimaryHost() + ":" + config.getPrimaryPort()
        );
        replicaSyncManager.start();
    }

    public void registerReplica(Socket socket) throws IOException {
        String id = socket.getRemoteSocketAddress()
                + "-" + replicaIds.incrementAndGet();
        replicaManager.register(new ReplicaConnection(id, socket));
    }

    public void lockPrimaryWrites() {
        primaryWriteLock.lock();
    }

    public void unlockPrimaryWrites() {
        primaryWriteLock.unlock();
    }

    public void registerReplicaAndSendSnapshot(
            Socket socket
    ) throws IOException {
        ReplicaConnection replica;

        primaryWriteLock.lock();
        try {
            String id = socket.getRemoteSocketAddress()
                    + "-" + replicaIds.incrementAndGet();
            replica = new ReplicaConnection(id, socket);
            replicaManager.register(replica);
        } finally {
            primaryWriteLock.unlock();
        }

        try {
            replica.writeSnapshotCommand(
                    RespCommandCodec.encode(
                            List.of("REPLICAHELLO", "OK")
                    )
            );

            int[] commandCount = {1};
            store.forEachSnapshot(entry -> {
                List<String> command = entry.expiresAt() == null
                        ? List.of("SET", entry.key(), entry.value())
                        : List.of(
                                "SET",
                                entry.key(),
                                entry.value(),
                                "PX",
                                String.valueOf(
                                        Math.max(
                                                1,
                                                entry.expiresAt()
                                                        - System.currentTimeMillis()
                                        )
                                )
                        );

                try {
                    replica.writeSnapshotCommand(
                            RespCommandCodec.encode(command)
                    );
                    commandCount[0]++;
                    if (commandCount[0] % 256 == 0) {
                        replica.flushSnapshot();
                    }
                } catch (IOException e) {
                    throw new SnapshotTransferException(e);
                }
            });

            replica.finishSnapshot();
        } catch (SnapshotTransferException e) {
            replicaManager.unregister(replica.getReplicaId());
            throw e.ioException();
        } catch (IOException e) {
            replicaManager.unregister(replica.getReplicaId());
            throw e;
        }
    }

    private static final class SnapshotTransferException
            extends RuntimeException {

        private SnapshotTransferException(IOException cause) {
            super(cause);
        }

        private IOException ioException() {
            return (IOException) super.getCause();
        }
    }

    public void replicate(List<String> command) {
        if (isPrimary()) {
            replicaManager.broadcast(
                    RespCommandCodec.encode(command),
                    replica -> replicaManager.unregister(
                            replica.getReplicaId()
                    )
            );
        }
    }

    public ReplicationRole getRole() {
        return config.getRole();
    }

    public boolean isPrimary() {
        return config.getRole() == ReplicationRole.PRIMARY;
    }

    public boolean isReplica() {
        return config.getRole() == ReplicationRole.REPLICA;
    }

    @PreDestroy
    public void shutdown() {
        if (isPrimary()) {
            replicaManager.closeAll();
        } else {
            replicaSyncManager.stop();
        }

        System.out.println("Replication manager stopped");
    }
}
