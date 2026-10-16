package me.niteshh.redcake.metrics;

import me.niteshh.redcake.cluster.ClusterState;
import me.niteshh.redcake.config.MemoryConfig;
import me.niteshh.redcake.persistence.AppendOnlyLog;
import me.niteshh.redcake.replication.primary.ReplicaManager;
import me.niteshh.redcake.stats.CommandStats;
import me.niteshh.redcake.stats.ReplicationStats;
import me.niteshh.redcake.stats.ServerStats;
import me.niteshh.redcake.stats.SlowLog;
import me.niteshh.redcake.store.KeyValueStore;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Renders RedCake's state in the Prometheus text exposition format
 * (version 0.0.4). Pure function of the shared statistics beans, so it can be
 * unit-tested without any HTTP server.
 *
 * <p>Metric names follow Prometheus conventions: {@code _total} for counters,
 * base units (seconds, bytes), and a cumulative histogram for command latency.
 */
@Component
public class MetricsRenderer {

    private final ServerStats serverStats;
    private final CommandStats commandStats;
    private final KeyValueStore store;
    private final ReplicationStats replicationStats;
    private final ReplicaManager replicaManager;
    private final AppendOnlyLog appendOnlyLog;
    private final MemoryConfig memoryConfig;
    private final SlowLog slowLog;
    private final ClusterState clusterState;

    /** Standalone wiring (no ZooKeeper). */
    public MetricsRenderer(
            ServerStats serverStats,
            CommandStats commandStats,
            KeyValueStore store,
            ReplicationStats replicationStats,
            ReplicaManager replicaManager,
            AppendOnlyLog appendOnlyLog,
            MemoryConfig memoryConfig,
            SlowLog slowLog
    ) {
        this(serverStats, commandStats, store, replicationStats, replicaManager, appendOnlyLog, memoryConfig,
                slowLog, ClusterState.standalone());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MetricsRenderer(
            ServerStats serverStats,
            CommandStats commandStats,
            KeyValueStore store,
            ReplicationStats replicationStats,
            ReplicaManager replicaManager,
            AppendOnlyLog appendOnlyLog,
            MemoryConfig memoryConfig,
            SlowLog slowLog,
            ClusterState clusterState
    ) {
        this.serverStats = serverStats;
        this.commandStats = commandStats;
        this.store = store;
        this.replicationStats = replicationStats;
        this.replicaManager = replicaManager;
        this.appendOnlyLog = appendOnlyLog;
        this.memoryConfig = memoryConfig;
        this.slowLog = slowLog;
        this.clusterState = clusterState;
    }

    /** @return the full {@code /metrics} response body */
    public String render() {
        StringBuilder out = new StringBuilder(4096);

        gauge(out,
                "redcake_uptime_seconds", "Seconds since start",
                serverStats.uptimeSeconds()
        );

        gauge(out, "redcake_connected_clients", "Currently connected clients", serverStats.connectedClients());
        counter(out, "redcake_connections_received_total", "Connections accepted", serverStats.totalConnections());
        counter(out, "redcake_connections_rejected_total", "Connections refused (maxclients)", serverStats.rejectedConnections());
        counter(out, "redcake_commands_processed_total", "Commands received", serverStats.totalCommands());
        counter(out, "redcake_auth_failures_total", "Failed AUTH attempts", serverStats.failedAuthentications());
        counter(out, "redcake_expired_keys_total", "Keys removed because their TTL elapsed", serverStats.expiredKeys());
        counter(out, "redcake_evicted_keys_total", "Keys evicted because of maxmemory", serverStats.evictedKeys());

        gauge(out, "redcake_keys", "Number of keys", store.size());
        gauge(out, "redcake_keys_with_ttl", "Number of keys with a TTL", store.expiringKeys());
        gauge(out, "redcake_memory_used_bytes", "Estimated bytes used by the dataset", store.usedMemory());
        gauge(out, "redcake_memory_limit_bytes", "Configured maxmemory (0 = unlimited)", memoryConfig.getMaxBytes());

        gauge(out, "redcake_replication_offset_bytes", "Replication stream offset", replicationStats.offset());
        gauge(out, "redcake_connected_replicas", "Replicas attached to this primary", replicaManager.replicaCount());
        gauge(out, "redcake_replica_link_up", "1 if this replica is synced with its primary",
                replicationStats.isMasterLinkUp() ? 1 : 0);
        gauge(out, "redcake_aof_enabled", "1 if the append-only file is active", appendOnlyLog.isActive() ? 1 : 0);
        gauge(out, "redcake_aof_healthy", "0 if the last AOF write failed", appendOnlyLog.isHealthy() ? 1 : 0);
        gauge(out, "redcake_slowlog_entries", "Entries currently in the slow log", slowLog.length());
        clusterMetrics(out);

        Runtime runtime = Runtime.getRuntime();
        gauge(out, "redcake_jvm_memory_used_bytes", "JVM heap in use", runtime.totalMemory() - runtime.freeMemory());
        gauge(out, "redcake_jvm_threads", "Live JVM threads", Thread.activeCount());

        commandCounters(out);
        latencyHistogram(out);
        return out.toString();
    }

    private void clusterMetrics(StringBuilder out) {
        gauge(out, "redcake_cluster_enabled", "1 if ZooKeeper-coordinated failover is on", clusterState.isManaged() ? 1 : 0);
        if (!clusterState.isManaged()) {
            return;
        }
        gauge(out, "redcake_cluster_is_primary", "1 if this node is the primary and may accept writes",
                clusterState.mayWrite() ? 1 : 0);
        gauge(out, "redcake_cluster_fenced", "1 if this node is the elected primary but must not write right now",
                clusterState.isFenced() ? 1 : 0);
        gauge(out, "redcake_cluster_epoch", "Leader epoch this node leads or follows (-1 = none)", clusterState.epoch());
        gauge(out, "redcake_cluster_zk_connected", "1 if the ZooKeeper connection is healthy",
                clusterState.zkState() == ClusterState.ZkState.CONNECTED ? 1 : 0);
        gauge(out, "redcake_cluster_members", "Nodes registered in ZooKeeper (as of the last election)", clusterState.members());
        gauge(out, "redcake_cluster_lease_remaining_milliseconds", "Time left of the primary's write lease",
                clusterState.leaseRemainingMillis());
        counter(out, "redcake_cluster_promotions_total", "Times this node became the primary", clusterState.promotions());
        counter(out, "redcake_cluster_demotions_total", "Times this node gave up the primary role", clusterState.demotions());
    }

    private void commandCounters(StringBuilder out) {
        header(out, "redcake_command_calls_total", "counter", "Calls per command");
        commandStats.snapshot().forEach((name, c) ->
                out.append("redcake_command_calls_total{command=\"").append(name.toLowerCase(Locale.ROOT))
                        .append("\"} ").append(c.calls()).append('\n'));

        header(out, "redcake_command_failed_total", "counter", "Calls that returned an error, per command");
        commandStats.snapshot().forEach((name, c) ->
                out.append("redcake_command_failed_total{command=\"").append(name.toLowerCase(Locale.ROOT))
                        .append("\"} ").append(c.failed()).append('\n'));
    }

    /** Cumulative buckets in seconds, as Prometheus requires. */
    private void latencyHistogram(StringBuilder out) {
        String name = "redcake_command_duration_seconds";
        header(out, name, "histogram", "Command execution time (excludes network)");
        long[] counts = commandStats.bucketCounts();
        long cumulative = 0;
        for (int i = 0; i < CommandStats.BUCKET_BOUNDS_MICROS.length; i++) {
            cumulative += counts[i];
            out.append(name).append("_bucket{le=\"")
                    .append(CommandStats.BUCKET_BOUNDS_MICROS[i] / 1_000_000.0).append("\"} ")
                    .append(cumulative).append('\n');
        }
        cumulative += counts[counts.length - 1];
        out.append(name).append("_bucket{le=\"+Inf\"} ").append(cumulative).append('\n');
        out.append(name).append("_sum ").append(commandStats.totalMicros() / 1_000_000.0).append('\n');
        out.append(name).append("_count ").append(cumulative).append('\n');
    }

    private static void gauge(StringBuilder out, String name, String help, long value) {
        header(out, name, "gauge", help);
        out.append(name).append(' ').append(value).append('\n');
    }

    private static void counter(StringBuilder out, String name, String help, long value) {
        header(out, name, "counter", help);
        out.append(name).append(' ').append(value).append('\n');
    }

    private static void header(StringBuilder out, String name, String type, String help) {
        out.append("# HELP ").append(name).append(' ').append(help).append('\n');
        out.append("# TYPE ").append(name).append(' ').append(type).append('\n');
    }
}
