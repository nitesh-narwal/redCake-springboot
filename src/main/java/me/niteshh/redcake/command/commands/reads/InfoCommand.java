package me.niteshh.redcake.command.commands.reads;

import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.config.MemoryConfig;
import me.niteshh.redcake.config.PersistenceConfig;
import me.niteshh.redcake.replication.ReplicationConfig;
import me.niteshh.redcake.replication.ReplicationRole;
import me.niteshh.redcake.replication.primary.ReplicaManager;
import me.niteshh.redcake.resp.BulkString;
import me.niteshh.redcake.resp.RespValue;
import me.niteshh.redcake.replication.primary.ReplicaConnection;
import me.niteshh.redcake.stats.CommandStats;
import me.niteshh.redcake.stats.ReplicationStats;
import me.niteshh.redcake.stats.ServerStats;
import me.niteshh.redcake.store.KeyValueStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * {@code INFO [section]} - human/monitoring readable server state in the
 * classic {@code # Section\r\nkey:value} format, so existing Redis dashboards
 * and {@code redis-cli INFO} work.
 *
 * <p>Sections: {@code server, clients, memory, persistence, stats,
 * replication, keyspace}; {@code all}/{@code default}/{@code everything}
 * print all of them; an unknown section yields an empty reply.
 */
@Component
public class InfoCommand implements RedCakeCommand {

    /** Reported as {@code redcake_version}. */
    public static final String VERSION = "0.2.0";

    private final KeyValueStore store;
    private final ReplicationConfig replicationConfig;
    private final ServerStats serverStats;
    private final ReplicationStats replicationStats;
    private final ReplicaManager replicaManager;
    private final PersistenceConfig persistenceConfig;
    private final MemoryConfig memoryConfig;
    private final CommandStats commandStats;

    @Autowired
    public InfoCommand(
            KeyValueStore store,
            ReplicationConfig replicationConfig,
            ServerStats serverStats,
            ReplicationStats replicationStats,
            ReplicaManager replicaManager,
            PersistenceConfig persistenceConfig,
            MemoryConfig memoryConfig,
            CommandStats commandStats
    ) {
        this.store = store;
        this.replicationConfig = replicationConfig;
        this.serverStats = serverStats;
        this.replicationStats = replicationStats;
        this.replicaManager = replicaManager;
        this.persistenceConfig = persistenceConfig;
        this.memoryConfig = memoryConfig;
        this.commandStats = commandStats;
    }

    /** Convenience constructor with standalone (empty) statistics; used by unit tests. */
    public InfoCommand(KeyValueStore store, ReplicationConfig replicationConfig) {
        this(
                store,
                replicationConfig,
                new ServerStats(),
                new ReplicationStats(),
                new ReplicaManager(),
                PersistenceConfig.disabled(),
                MemoryConfig.unlimited(),
                new CommandStats()
        );
    }

    @Override
    public String name() {
        return "INFO";
    }

    @Override
    public RespValue execute(java.util.List<String> arguments) {
        if (arguments.size() > 1) {
            return CommandSupport.wrongArity("info");
        }

        String section = arguments.isEmpty()
                ? "all"
                : arguments.getFirst().toLowerCase(Locale.ROOT);

        String response = switch (section) {
            case "all", "default", "everything" -> allInfo();
            case "server" -> serverInfo();
            case "clients" -> clientsInfo();
            case "memory" -> memoryInfo();
            case "persistence" -> persistenceInfo();
            case "stats" -> statsInfo();
            case "commandstats" -> commandStatsInfo();
            case "replication" -> replicationInfo();
            case "keyspace" -> keyspaceInfo();
            default -> "";
        };
        return new BulkString(response);
    }

    private String allInfo() {
        return serverInfo() + clientsInfo() + memoryInfo() + persistenceInfo()
                + statsInfo() + replicationInfo() + commandStatsInfo() + keyspaceInfo();
    }

    private String serverInfo() {
        return "# Server\r\n"
                + "redcake_version:" + VERSION + "\r\n"
                + "os:" + System.getProperty("os.name") + "\r\n"
                + "java_version:" + System.getProperty("java.version") + "\r\n"
                + "uptime_in_seconds:" + serverStats.uptimeSeconds() + "\r\n"
                + "\r\n";
    }

    private String clientsInfo() {
        return "# Clients\r\n"
                + "connected_clients:" + serverStats.connectedClients() + "\r\n"
                + "rejected_connections:" + serverStats.rejectedConnections() + "\r\n"
                + "\r\n";
    }

    private String memoryInfo() {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        return "# Memory\r\n"
                + "used_memory:" + store.usedMemory() + "\r\n"
                + "maxmemory:" + memoryConfig.getMaxBytes() + "\r\n"
                + "maxmemory_policy:" + memoryConfig.getPolicy().configName() + "\r\n"
                + "used_memory_jvm:" + used + "\r\n"
                + "max_memory_jvm:" + runtime.maxMemory() + "\r\n"
                + "\r\n";
    }

    private String persistenceInfo() {
        return "# Persistence\r\n"
                + "aof_enabled:" + (persistenceConfig.isEnabled() ? 1 : 0) + "\r\n"
                + (persistenceConfig.isEnabled()
                ? "aof_file:" + persistenceConfig.getFile() + "\r\n"
                + "aof_fsync:" + persistenceConfig.getFsync().name().toLowerCase(Locale.ROOT) + "\r\n"
                : "")
                + "\r\n";
    }

    private String statsInfo() {
        return "# Stats\r\n"
                + "total_connections_received:" + serverStats.totalConnections() + "\r\n"
                + "total_commands_processed:" + serverStats.totalCommands() + "\r\n"
                + "failed_authentications:" + serverStats.failedAuthentications() + "\r\n"
                + "expired_keys:" + serverStats.expiredKeys() + "\r\n"
                + "evicted_keys:" + serverStats.evictedKeys() + "\r\n"
                + "\r\n";
    }

    private String replicationInfo() {
        boolean replica = replicationConfig.getRole() == ReplicationRole.REPLICA;
        StringBuilder info = new StringBuilder("# Replication\r\n");
        info.append("role:").append(replica ? "replica" : "primary").append("\r\n");

        if (replica) {
            info.append("master_host:").append(replicationConfig.getPrimaryHost()).append("\r\n");
            info.append("master_port:").append(replicationConfig.getPrimaryPort()).append("\r\n");
            info.append("master_link_status:")
                    .append(replicationStats.isMasterLinkUp() ? "up" : "down")
                    .append("\r\n");
        } else {
            info.append("connected_slaves:").append(replicaManager.replicaCount()).append("\r\n");
            int index = 0;
            for (ReplicaConnection link : replicaManager.getReplicas()) {
                info.append("slave").append(index++).append(":ip=").append(link.getRemoteHost())
                        .append(",port=").append(link.getListeningPort())
                        .append(",state=").append(link.isConnected() ? "online" : "offline")
                        .append(",offset=").append(link.getAckOffset())
                        .append(",lag=").append(link.getLagSeconds())
                        .append(",pending_bytes=").append(link.getPendingBytes())
                        .append("\r\n");
            }
        }

        info.append("master_replid:").append(replicationStats.replid()).append("\r\n");
        info.append("master_repl_offset:").append(replicationStats.offset()).append("\r\n");
        var backlog = replicationStats.backlog();
        if (!replica && backlog != null) {
            info.append("repl_backlog_active:1\r\n");
            info.append("repl_backlog_size:").append(replicationConfig.getBacklogBytes()).append("\r\n");
            info.append("repl_backlog_first_byte_offset:").append(backlog.startOffset()).append("\r\n");
            info.append("repl_backlog_histlen:").append(backlog.endOffset() - backlog.startOffset()).append("\r\n");
        }
        info.append("\r\n");
        return info.toString();
    }

    /** {@code cmdstat_<name>:calls=..,usec=..,usec_per_call=..,failed_calls=..} per command. */
    private String commandStatsInfo() {
        StringBuilder info = new StringBuilder("# Commandstats\r\n");
        commandStats.snapshot().forEach((name, counter) -> {
            long calls = counter.calls();
            info.append("cmdstat_").append(name.toLowerCase(Locale.ROOT))
                    .append(":calls=").append(calls)
                    .append(",usec=").append(counter.micros())
                    .append(",usec_per_call=").append(calls == 0 ? 0 : counter.micros() / calls)
                    .append(",failed_calls=").append(counter.failed())
                    .append("\r\n");
        });
        return info.append("\r\n").toString();
    }

    private String keyspaceInfo() {
        return "# Keyspace\r\n"
                + "db0:keys=" + store.size() + ",expires=" + store.expiringKeys() + "\r\n\r\n";
    }
}
