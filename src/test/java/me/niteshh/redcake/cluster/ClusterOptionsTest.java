package me.niteshh.redcake.cluster;

import me.niteshh.redcake.cli.CommandLineConfig;
import me.niteshh.redcake.replication.ReplicationRole;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.junit.jupiter.api.Assertions.*;

/** The cluster command-line options and the cluster settings' own validation. */
class ClusterOptionsTest {

    private static CommandLineConfig parse(String... args) {
        return new CommandLineConfig(new DefaultApplicationArguments(args));
    }

    @Test
    void clusterModeIsOffByDefault() {
        CommandLineConfig config = parse();

        assertFalse(config.getClusterConfig().isEnabled());
        assertFalse(config.getReplicationConfig().isCoordinated());
        assertEquals(ReplicationRole.PRIMARY, config.getReplicationConfig().getRole());
    }

    @Test
    void zkConnectTurnsTheNodeIntoAnUndecidedReplicaWithoutAPrimary() {
        CommandLineConfig config = parse("--port", "6400", "--zk-connect", "zk1:2181,zk2:2181/prod",
                "--advertise-host", "node-a");

        ClusterConfig cluster = config.getClusterConfig();
        assertTrue(cluster.isEnabled());
        assertEquals("zk1:2181,zk2:2181/prod", cluster.getConnectString());
        assertEquals("node-a", cluster.getAdvertiseHost());
        assertEquals(6400, cluster.getAdvertisePort(), "advertised port defaults to --port");
        assertEquals("node-a:6400", cluster.getNodeId(), "node id defaults to the advertised address");
        assertEquals(ClusterConfig.DEFAULT_CLUSTER_NAME, cluster.getClusterName());
        assertEquals(ClusterConfig.DEFAULT_REPLICA_PRIORITY, cluster.getReplicaPriority());

        assertEquals(ReplicationRole.REPLICA, config.getReplicationConfig().getRole());
        assertNull(config.getReplicationConfig().getPrimaryHost(), "ZooKeeper assigns the primary");
        assertTrue(config.getReplicationConfig().isCoordinated());
    }

    @Test
    void allClusterOptionsAreParsed() {
        ClusterConfig cluster = parse("--zk-connect", "zk:2181", "--cluster-name", "orders", "--node-id", "n7",
                "--advertise-host", "h", "--advertise-port", "7000", "--replica-priority", "0",
                "--zk-session-timeout-ms", "20000", "--zk-connection-timeout-ms", "2500",
                "--cluster-election-delay-ms", "250").getClusterConfig();

        assertEquals("orders", cluster.getClusterName());
        assertEquals("n7", cluster.getNodeId());
        assertEquals(7000, cluster.getAdvertisePort());
        assertEquals(0, cluster.getReplicaPriority());
        assertEquals(20_000, cluster.getSessionTimeoutMs());
        assertEquals(2_500, cluster.getConnectionTimeoutMs());
        assertEquals(250, cluster.getElectionDelayMs());
    }

    @Test
    void aWildcardBindIsNotAdvertisedAsIs() {
        ClusterConfig cluster = parse("--bind", "0.0.0.0", "--allow-insecure", "--zk-connect", "zk:2181")
                .getClusterConfig();

        assertNotEquals("0.0.0.0", cluster.getAdvertiseHost());
        assertFalse(cluster.getAdvertiseHost().isBlank());
    }

    @Test
    void replicaofAndZkConnectAreMutuallyExclusive() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse("--zk-connect", "zk:2181", "--replicaof", "10.0.0.1", "6379"));

        assertTrue(error.getMessage().contains("ZooKeeper decides"), error.getMessage());
    }

    @Test
    void invalidClusterOptionsAreRejectedWithAClearMessage() {
        assertThrows(IllegalArgumentException.class, () -> parse("--zk-connect", "zk:2181", "--cluster-name", "a/b"));
        assertThrows(IllegalArgumentException.class, () -> parse("--zk-connect", "zk:2181", "--node-id", "has space"));
        assertThrows(IllegalArgumentException.class, () -> parse("--zk-connect", "zk:2181", "--replica-priority", "-1"));
        assertThrows(IllegalArgumentException.class, () -> parse("--zk-connect", "zk:2181", "--zk-session-timeout-ms", "10"));
        assertThrows(IllegalArgumentException.class, () -> parse("--zk-connect", "zk:2181", "--advertise-port", "70000"));
        assertThrows(IllegalArgumentException.class, () -> parse("--zk-connect", " "));
        assertThrows(IllegalArgumentException.class, () -> parse("--zk-connect"));
    }

    @Test
    void zookeeperCredentialsMustLookLikeUserColonPassword(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Path good = dir.resolve("zk.auth");
        java.nio.file.Files.writeString(good, "redcake:s3cret\n");
        assertEquals("redcake:s3cret",
                parse("--zk-connect", "zk:2181", "--zk-auth-file", good.toString()).getClusterConfig().getZkAuth());

        java.nio.file.Path bad = dir.resolve("bad.auth");
        java.nio.file.Files.writeString(bad, "no-colon-here");
        assertThrows(IllegalArgumentException.class,
                () -> parse("--zk-connect", "zk:2181", "--zk-auth-file", bad.toString()));
    }

    @Test
    void minReplicasOptionsReachTheReplicationConfig() {
        var replication = parse("--min-replicas-to-write", "2", "--min-replicas-max-lag", "5").getReplicationConfig();

        assertEquals(2, replication.getMinReplicasToWrite());
        assertEquals(5, replication.getMinReplicasMaxLagSeconds());
        assertEquals(0, parse().getReplicationConfig().getMinReplicasToWrite());
    }

    @Test
    void theTimingsAreDerivedSoALeaderStopsBeforeItsSessionCanExpire() {
        ClusterConfig cluster = new ClusterConfig("zk:2181", "c", "n", "h", 6379, 100, 12_000, 5_000, 1_000, null);

        assertEquals(8_000, cluster.leaseMillis(), "2/3 of the session timeout");
        assertTrue(cluster.leaseMillis() < cluster.getSessionTimeoutMs());
        assertTrue(cluster.leaseRenewMillis() * 3 <= cluster.leaseMillis(), "several renewals fit into one lease");
        assertTrue(cluster.tickMillis() <= 500);
        assertTrue(cluster.staggerMillis() >= cluster.getElectionDelayMs());
    }
}
