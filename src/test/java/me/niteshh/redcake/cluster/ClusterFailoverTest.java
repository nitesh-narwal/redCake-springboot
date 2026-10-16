package me.niteshh.redcake.cluster;

import me.niteshh.redcake.support.RespClient;
import me.niteshh.redcake.support.TestSupport;
import me.niteshh.redcake.support.ZkProxy;
import org.apache.curator.test.InstanceSpec;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Failover end to end: several complete RedCake nodes (real replication, real sockets, real coordinator)
 * against an embedded ZooKeeper. Every test uses its own cluster name, so they share one ZooKeeper server
 * without seeing each other's nodes.
 */
class ClusterFailoverTest {

    private static final AtomicInteger CLUSTERS = new AtomicInteger();
    private static final long WAIT_MS = 20_000;

    private static TestingServer zookeeper;

    private final List<ClusterNode> nodes = new ArrayList<>();
    private final List<ZkProxy> proxies = new ArrayList<>();
    private final Map<String, ZkProxy> proxyOf = new HashMap<>();
    private String cluster;

    @BeforeAll
    static void startZooKeeper() throws Exception {
        // tickTime 200 ms: ZooKeeper accepts session timeouts from 400 ms, so the tests can use short ones.
        zookeeper = new TestingServer(new InstanceSpec(null, -1, -1, -1, true, -1, 200, -1), true);
    }

    @AfterAll
    static void stopZooKeeper() throws Exception {
        zookeeper.close();
    }

    @BeforeEach
    void newCluster() {
        cluster = "test" + CLUSTERS.incrementAndGet();
    }

    @AfterEach
    void shutDown() {
        nodes.forEach(ClusterNode::close);
        proxies.forEach(ZkProxy::close);
    }

    // ------------------------------------------------------------- helpers

    private ClusterNode.Builder builder(String id) {
        return ClusterNode.builder(zookeeper.getConnectString(), cluster, id);
    }

    private ClusterNode start(ClusterNode.Builder builder) throws Exception {
        ClusterNode node = builder.build();
        nodes.add(node);
        return node.start();
    }

    private ClusterNode start(String id) throws Exception {
        return start(builder(id));
    }

    /** A node whose ZooKeeper traffic goes through a proxy the test can cut. */
    private ClusterNode startBehindProxy(String id) throws Exception {
        return startBehindProxy(id, ClusterNode.SESSION_MS);
    }

    /**
     * The proxy is listed twice, the way the members of an ensemble are: a ZooKeeper client that knows a single
     * address waits at least a second before it tries that address again, so with a 2 s session almost any
     * connection drop would cost the session and the test would not be about what it says.
     */
    private ClusterNode startBehindProxy(String id, int sessionMs) throws Exception {
        ZkProxy proxy = proxy();
        proxyOf.put(id, proxy);
        String ensemble = proxy.connectString() + "," + proxy.connectString();
        return start(ClusterNode.builder(ensemble, cluster, id).sessionMs(sessionMs));
    }

    /** A node on a real ensemble whose session has to outlast a member being restarted (the client must reconnect first). */
    private ClusterNode startOnEnsemble(String connectString, String id) throws Exception {
        return start(ClusterNode.builder(connectString, cluster, id).sessionMs(ClusterNode.LONG_SESSION_MS));
    }

    /** Sends one command to a node; connection problems count as "no usable reply". */
    private static String reply(ClusterNode node, String... command) {
        try (RespClient client = node.client()) {
            return client.call(command);
        } catch (Exception e) {
            return "(" + e + ")";
        }
    }

    private static void writeAndWaitForReplicas(ClusterNode leader, int replicas, String... command) throws Exception {
        try (RespClient client = leader.client()) {
            assertEquals("+OK", client.call(command));
            assertEquals(":" + replicas, client.call("WAIT", Integer.toString(replicas), "5000"));
        }
    }

    private ZkProxy proxy() throws Exception {
        String[] hostPort = zookeeper.getConnectString().split(":");
        ZkProxy proxy = new ZkProxy(hostPort[0], Integer.parseInt(hostPort[1]));
        proxies.add(proxy);
        return proxy;
    }

    private static long leaders(ClusterNode... nodes) {
        return List.of(nodes).stream().filter(ClusterNode::isLeader).count();
    }

    /** Waits until exactly one of the nodes is a writable leader and returns it. */
    private static ClusterNode awaitLeader(ClusterNode... nodes) throws Exception {
        assertTrue(TestSupport.await(WAIT_MS, () -> leaders(nodes) == 1), "no single leader: " + describe(nodes));
        return List.of(nodes).stream().filter(ClusterNode::isLeader).findFirst().orElseThrow();
    }

    /** Waits until {@code follower} is attached to {@code leader} and holds a trusted dataset. */
    private static void awaitFollowing(ClusterNode follower, ClusterNode leader) throws Exception {
        assertTrue(TestSupport.await(WAIT_MS, () -> follower.isFollowing()
                        && follower.state.leaderAddress().equals("127.0.0.1:" + leader.port)
                        && follower.replicationStats.isMasterLinkUp()
                        && follower.state.isDataTrusted()),
                follower.id + " is not following " + leader.id + ": " + describe(follower, leader));
    }

    private static String describe(ClusterNode... nodes) {
        StringBuilder text = new StringBuilder();
        for (ClusterNode node : nodes) {
            text.append('\n').append(node.id).append(": mode=").append(node.state.mode())
                    .append(" zk=").append(node.state.zkState())
                    .append(" epoch=").append(node.state.epoch())
                    .append(" mayWrite=").append(node.state.mayWrite())
                    .append(" election=").append(node.state.electionStatus());
        }
        return text.toString();
    }

    private static List<ClusterNode> others(ClusterNode leader, ClusterNode... all) {
        return List.of(all).stream().filter(node -> node != leader).toList();
    }

    // --------------------------------------------------------------- tests

    @Test
    void aNewClusterElectsExactlyOneLeaderAndTheOthersFollowAndReplicate() throws Exception {
        ClusterNode a = start("a");
        ClusterNode b = start("b");
        ClusterNode c = start("c");

        ClusterNode leader = awaitLeader(a, b, c);
        for (ClusterNode follower : others(leader, a, b, c)) {
            awaitFollowing(follower, leader);
        }
        assertEquals(1, leaders(a, b, c));
        assertEquals(1, leader.state.epoch(), "the first leader of a cluster has epoch 1");

        try (RespClient client = leader.client()) {
            assertEquals("+OK", client.call("SET", "greeting", "hello"));
            assertEquals(":2", client.call("WAIT", "2", "5000"), "both replicas acknowledge");
        }
        for (ClusterNode follower : others(leader, a, b, c)) {
            assertEquals("hello", follower.get("greeting"));
        }
    }

    @Test
    void aFollowerRefusesWritesAndNamesThePrimaryAndInfoDescribesTheCluster() throws Exception {
        ClusterNode a = start("a");
        ClusterNode b = start("b");
        ClusterNode leader = awaitLeader(a, b);
        ClusterNode follower = others(leader, a, b).getFirst();
        awaitFollowing(follower, leader);

        String refused = reply(follower, "SET", "k", "v");
        assertEquals("-READONLY replica does not accept writes (the primary is 127.0.0.1:" + leader.port + ")", refused);
        assertTrue(reply(follower, "REPLICAOF", "NO", "ONE").startsWith("-ERR REPLICAOF is disabled"),
                "ZooKeeper decides the roles, an operator command must not fight it");

        String info = reply(leader, "INFO", "cluster");
        assertTrue(info.contains("cluster_enabled:1"), info);
        assertTrue(info.contains("cluster_state:leading"), info);
        assertTrue(info.contains("cluster_fenced:0"), info);
        assertTrue(info.contains("cluster_epoch:1"), info);
        assertTrue(info.contains("cluster_leader:127.0.0.1:" + leader.port), info);
        assertTrue(reply(follower, "INFO", "cluster").contains("cluster_state:following"));
        assertTrue(reply(leader, "ROLE").contains("master"));
    }

    @Test
    void aCrashedPrimaryIsReplacedByAReplicaAndTheOtherReplicaResumesWithoutACopy() throws Exception {
        ClusterNode a = startBehindProxy("a");
        ClusterNode b = startBehindProxy("b");
        ClusterNode c = startBehindProxy("c");
        ClusterNode leader = awaitLeader(a, b, c);
        List<ClusterNode> followers = others(leader, a, b, c);
        for (ClusterNode follower : followers) {
            awaitFollowing(follower, leader);
        }
        try (RespClient client = leader.client()) {
            for (int i = 0; i < 50; i++) {
                assertEquals("+OK", client.call("SET", "key-" + i, "value-" + i));
            }
            assertEquals(":2", client.call("WAIT", "2", "5000"));
        }
        // A key that exists only on one replica: a full resync would wipe it, a partial one keeps it.
        for (ClusterNode follower : followers) {
            follower.stack.store().set("marker-" + follower.id, "x");
        }

        // Hard crash: the port closes and ZooKeeper is never told. Its session has to time out.
        proxyOf.get(leader.id).cut();
        leader.crash();

        ClusterNode successor = awaitLeader(followers.toArray(new ClusterNode[0]));
        ClusterNode other = others(successor, followers.toArray(new ClusterNode[0])).getFirst();
        assertEquals(2, successor.state.epoch());
        awaitFollowing(other, successor);

        for (int i = 0; i < 50; i++) {
            assertEquals("value-" + i, successor.get("key-" + i), "the new primary lost an acknowledged write");
        }
        assertEquals("x", other.get("marker-" + other.id),
                "the other replica must continue from the new primary, not copy the dataset again");
        assertEquals(successor.replicationStats.replid(), other.replicationStats.replid());
        assertNotEquals("", successor.replicationStats.replid2(), "the new primary honours the old history");

        writeAndWaitForReplicas(successor, 1, "SET", "after-failover", "yes");
        assertEquals("yes", other.get("after-failover"));
        assertEquals(1, successor.state.promotions());
    }

    @Test
    void aPrimaryCutOffFromZooKeeperStopsWritingAtOnceThenStepsDownAndRejoinsAsAReplica() throws Exception {
        ClusterNode a = startBehindProxy("a");
        ClusterNode b = startBehindProxy("b");
        ClusterNode c = startBehindProxy("c");
        ClusterNode zombie = awaitLeader(a, b, c);
        List<ClusterNode> survivors = others(zombie, a, b, c);
        for (ClusterNode follower : survivors) {
            awaitFollowing(follower, zombie);
        }
        writeAndWaitForReplicas(zombie, 2, "SET", "before", "1");

        // The worst case: the primary acknowledged a write that no replica has, and is then cut off.
        zombie.replicaManager.closeAll();
        zombie.replication.lockPrimaryWrites();
        try {
            zombie.stack.store().set("lost", "1");
            zombie.replication.replicate(List.of("SET", "lost", "1"));
        } finally {
            zombie.replication.unlockPrimaryWrites();
        }
        proxyOf.get(zombie.id).cut();

        // 1. Fail closed: no write is acknowledged any more, although the process is perfectly healthy.
        assertTrue(TestSupport.await(5_000, () -> reply(zombie, "SET", "x", "1").startsWith("-CLUSTERDOWN")),
                "a primary that cannot reach ZooKeeper must refuse writes: " + describe(zombie));
        assertTrue(reply(zombie, "SET", "after-fence", "1").startsWith("-CLUSTERDOWN"));
        assertNull(zombie.get("after-fence"), "nothing is acknowledged once the fence is closed");

        // 2. ZooKeeper expires its session; the others elect a successor without it.
        ClusterNode successor = awaitLeader(survivors.toArray(new ClusterNode[0]));
        assertEquals(2, successor.state.epoch());
        assertNull(successor.get("lost"), "the unreplicated write is not on the new primary");
        writeAndWaitForReplicas(successor, 1, "SET", "after", "2");

        // 3. The cut-off node does not keep believing it is the primary.
        assertTrue(TestSupport.await(WAIT_MS, () -> zombie.state.demotions() == 1), describe(zombie));
        assertTrue(reply(zombie, "SET", "x", "1").startsWith("-CLUSTERDOWN"), reply(zombie, "SET", "x", "1"));

        // 4. The partition heals: the old primary becomes a replica of the new one and drops what only it had.
        proxyOf.get(zombie.id).heal();
        awaitFollowing(zombie, successor);
        assertTrue(TestSupport.await(WAIT_MS, () -> "2".equals(zombie.get("after"))), "it must catch up");
        assertEquals("1", zombie.get("before"));
        assertNull(zombie.get("lost"), "the write nobody else has must disappear (full resync)");
        assertTrue(reply(zombie, "SET", "x", "1").startsWith("-READONLY"));
    }

    @Test
    void aBriefZooKeeperOutageFencesThePrimaryButItResumesWithoutAnElection() throws Exception {
        // The ZooKeeper client needs up to about a second to reconnect, so the session must be clearly longer than that.
        ClusterNode a = startBehindProxy("a", ClusterNode.LONG_SESSION_MS);
        ClusterNode b = startBehindProxy("b", ClusterNode.LONG_SESSION_MS);
        ClusterNode leader = awaitLeader(a, b);
        awaitFollowing(others(leader, a, b).getFirst(), leader);
        long epoch = leader.state.epoch();

        proxyOf.get(leader.id).cut();
        assertTrue(TestSupport.await(5_000, () -> reply(leader, "SET", "x", "1").startsWith("-CLUSTERDOWN")));
        proxyOf.get(leader.id).heal(); // well inside the session timeout: the session survives

        assertTrue(TestSupport.await(WAIT_MS, () -> "+OK".equals(reply(leader, "SET", "x", "2"))),
                "the primary must resume once it has proven its leadership again: " + describe(leader));
        assertEquals(epoch, leader.state.epoch(), "no election happened");
        assertEquals(0, leader.state.demotions());
        assertEquals(1, leader.state.promotions());
    }

    @Test
    void aPlannedFailoverMovesThePrimaryRoleWithoutLosingOrCopyingAnything() throws Exception {
        ClusterNode a = start("a");
        ClusterNode b = start("b");
        ClusterNode c = start("c");
        ClusterNode leader = awaitLeader(a, b, c);
        List<ClusterNode> followers = others(leader, a, b, c);
        for (ClusterNode follower : followers) {
            awaitFollowing(follower, leader);
        }
        try (RespClient client = leader.client()) {
            for (int i = 0; i < 100; i++) {
                assertEquals("+OK", client.call("SET", "key-" + i, Integer.toString(i)));
            }
        }
        leader.stack.store().set("old-primary-marker", "x"); // survives only if it resumes instead of copying

        ClusterNode target = followers.getFirst();
        ClusterNode bystander = followers.get(1);
        assertTrue(reply(followers.get(1), "FAILOVER").startsWith("-ERR FAILOVER refused: this node is not the primary"));
        assertTrue(reply(leader, "FAILOVER", "TO", "127.0.0.1", "1").contains("no cluster member"));
        assertEquals("+OK", reply(leader, "FAILOVER", "TO", "127.0.0.1", Integer.toString(target.port)));

        assertTrue(TestSupport.await(WAIT_MS, target::isLeader), describe(a, b, c));
        assertEquals(2, target.state.epoch());
        awaitFollowing(leader, target);
        awaitFollowing(bystander, target);

        for (int i = 0; i < 100; i++) {
            assertEquals(Integer.toString(i), target.get("key-" + i));
        }
        assertEquals("x", leader.get("old-primary-marker"), "the former primary continued, it did not resync from scratch");
        writeAndWaitForReplicas(target, 2, "SET", "after", "1");
        assertEquals("1", leader.get("after"));
        assertEquals("1", bystander.get("after"));
        assertTrue(reply(leader, "SET", "x", "1").startsWith("-READONLY"));
    }

    @Test
    void shuttingDownThePrimaryGracefullyHandsOverWithoutWaitingForASessionTimeout() throws Exception {
        ClusterNode a = start("a");
        ClusterNode b = start("b");
        ClusterNode c = start("c");
        ClusterNode leader = awaitLeader(a, b, c);
        List<ClusterNode> followers = others(leader, a, b, c);
        for (ClusterNode follower : followers) {
            awaitFollowing(follower, leader);
        }
        writeAndWaitForReplicas(leader, 2, "SET", "k", "v");

        long start = System.nanoTime();
        leader.shutdown();
        ClusterNode successor = awaitLeader(followers.toArray(new ClusterNode[0]));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertEquals("v", successor.get("k"));
        assertTrue(elapsedMillis < ClusterNode.SESSION_MS,
                "a graceful hand-over must not wait for the session to expire, took " + elapsedMillis + " ms");
        awaitFollowing(others(successor, followers.toArray(new ClusterNode[0])).getFirst(), successor);
    }

    @Test
    void aRestartedPrimaryRemovesItsOwnGhostLeaderNodeInsteadOfMakingEveryoneWait() throws Exception {
        ClusterNode a = startBehindProxy("a");
        ClusterNode b = start("b");
        ClusterNode leader = awaitLeader(a, b);
        if (leader != a) {
            // Make "a" the leader: stop b and let a win, then bring b back as a follower.
            b.shutdown();
            assertTrue(TestSupport.await(WAIT_MS, a::isLeader));
            b = start(builder("b"));
        }
        ClusterNode follower = b;
        awaitFollowing(follower, a);
        writeAndWaitForReplicas(a, 1, "SET", "k", "v");

        // "a" dies without saying goodbye; its leader znode lingers until its session times out.
        proxyOf.get("a").cut();
        a.crash();
        long start = System.nanoTime();
        ClusterNode reborn = start("a"); // same node id, new process, connects directly

        ClusterNode successor = awaitLeader(follower, reborn);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMillis < ClusterNode.SESSION_MS * 3 / 4,
                "the ghost leader node must be removed at once, took " + elapsedMillis + " ms");
        assertEquals("v", successor.get("k"), "the replica that held the data wins over the empty newcomer");
        assertSame(follower, successor);
        awaitFollowing(reborn, follower);
    }

    @Test
    void aNodeWithOlderDataThanTheLastLeaderCanNeverTakeOver(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        // The cluster has already seen epoch 5; this node's append-only file was written during epoch 3.
        try (var curator = org.apache.curator.framework.CuratorFrameworkFactory.newClient(
                zookeeper.getConnectString(), new org.apache.curator.retry.RetryOneTime(100))) {
            curator.start();
            curator.create().creatingParentsIfNeeded().forPath("/redcake/" + cluster + "/epoch", "5\n".getBytes());
        }
        Path staleAof = dir.resolve("stale.aof");
        ClusterEpochFile.write(ClusterEpochFile.forAof(staleAof), 3);
        ClusterNode stale = start(builder("stale").aof(staleAof));

        Thread.sleep(1_500); // plenty of time for a (wrong) election to happen
        assertFalse(stale.isLeader());
        assertEquals(ClusterState.Mode.ELECTING, stale.state.mode());
        assertTrue(stale.state.electionStatus().contains("older"), stale.state.electionStatus());
        assertTrue(reply(stale, "SET", "k", "v").startsWith("-LOADING"), "a node that has not joined takes no commands");
        assertTrue(reply(stale, "GET", "k").startsWith("-LOADING"), "stale data must not be served either");

        // A node whose data is current can lead, and the stale one then copies it.
        Path currentAof = dir.resolve("current.aof");
        ClusterEpochFile.write(ClusterEpochFile.forAof(currentAof), 5);
        ClusterNode current = start(builder("current").aof(currentAof).seed("k", "fresh"));
        assertTrue(TestSupport.await(WAIT_MS, current::isLeader), describe(stale, current));
        assertEquals(6, current.state.epoch());
        assertEquals(6, ClusterEpochFile.read(ClusterEpochFile.forAof(currentAof)),
                "the leader records its epoch next to its AOF before it accepts a write");
        awaitFollowing(stale, current);
        assertEquals("fresh", stale.get("k"));
    }

    @Test
    void aColdStartPicksTheNodeThatActuallyHoldsData() throws Exception {
        // A long settle period so all three nodes are registered before anybody ranks.
        ClusterNode empty1 = start(builder("a").electionDelayMs(1_500));
        ClusterNode withData = start(builder("z").electionDelayMs(1_500).seed("existing", "data"));
        ClusterNode empty2 = start(builder("b").electionDelayMs(1_500));

        ClusterNode leader = awaitLeader(empty1, withData, empty2);

        assertSame(withData, leader, "otherwise the empty nodes would wipe the data");
        awaitFollowing(empty1, withData);
        assertEquals("data", empty1.get("existing"));
    }

    @Test
    void aNodeWithPriorityZeroNeverTakesOver() throws Exception {
        ClusterNode a = startBehindProxy("a");
        ClusterNode dr = start(builder("dr").priority(0));
        ClusterNode leader = awaitLeader(a, dr);
        assertSame(a, leader, "the priority-0 node is not even a candidate");
        awaitFollowing(dr, a);

        proxyOf.get("a").cut();
        a.crash();
        Thread.sleep(ClusterNode.SESSION_MS + 1_500);

        assertFalse(dr.isLeader(), "never promote a priority-0 node");
        assertEquals(ClusterState.Mode.ELECTING, dr.state.mode());
        assertTrue(dr.state.electionStatus().contains("replica-priority is 0"), dr.state.electionStatus());
        assertTrue(reply(dr, "SET", "k", "v").startsWith("-CLUSTERDOWN"));
        assertEquals("+OK", reply(dr, "PING").equals("+PONG") ? "+OK" : reply(dr, "PING"), "reads and PING still work");

        // A normal node appears and takes the role; the priority-0 node follows it.
        ClusterNode fresh = start("fresh");
        assertTrue(TestSupport.await(WAIT_MS, fresh::isLeader), describe(dr, fresh));
        awaitFollowing(dr, fresh);
    }

    @Test
    void aNodeThatCannotReachZooKeeperAtStartupStaysOutOfService() throws Exception {
        ClusterNode lonely = ClusterNode.builder("127.0.0.1:1", cluster, "lonely").build();
        nodes.add(lonely);
        lonely.start();

        Thread.sleep(800);
        assertEquals(ClusterState.Mode.JOINING, lonely.state.mode());
        assertTrue(reply(lonely, "SET", "k", "v").startsWith("-LOADING"));
        assertTrue(reply(lonely, "GET", "k").startsWith("-LOADING"));
        assertEquals("+PONG", reply(lonely, "PING"));
        assertTrue(reply(lonely, "INFO", "cluster").contains("cluster_state:joining"));
    }

    @Test
    void aSilentPartitionIsDetectedByTheLeaseBeforeTheSessionCouldHaveExpired() throws Exception {
        // No reset, no error: the connection just goes quiet, like a dropped-packets partition or a frozen
        // process. Nothing announces it, so only the lease can stop the primary - and it must do so before
        // ZooKeeper could have given the role to somebody else (the session timeout).
        ClusterNode a = startBehindProxy("a");
        ClusterNode b = startBehindProxy("b");
        ClusterNode leader = awaitLeader(a, b);
        awaitFollowing(others(leader, a, b).getFirst(), leader);
        writeAndWaitForReplicas(leader, 1, "SET", "k", "v");

        long start = System.nanoTime();
        proxyOf.get(leader.id).blackhole();
        assertTrue(TestSupport.await(ClusterNode.SESSION_MS, () -> reply(leader, "SET", "x", "1").startsWith("-CLUSTERDOWN")),
                "the silent primary must refuse writes within the session timeout: " + describe(leader));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMillis < ClusterNode.SESSION_MS, "fenced after " + elapsedMillis + " ms");
        assertFalse(leader.state.mayWrite());
    }

    @Test
    void aLongZooKeeperOutageEndsInANewElectionWithoutLosingOrCopyingAnyData() throws Exception {
        ClusterNode a = startBehindProxy("a");
        ClusterNode b = startBehindProxy("b");
        ClusterNode c = startBehindProxy("c");
        ClusterNode oldLeader = awaitLeader(a, b, c);
        for (ClusterNode follower : others(oldLeader, a, b, c)) {
            awaitFollowing(follower, oldLeader);
        }
        try (RespClient client = oldLeader.client()) {
            for (int i = 0; i < 20; i++) {
                assertEquals("+OK", client.call("SET", "key-" + i, "v" + i));
            }
            assertEquals(":2", client.call("WAIT", "2", "5000"));
        }
        for (ClusterNode node : List.of(a, b, c)) {
            node.stack.store().set("marker-" + node.id, "x"); // a full resync would wipe the replicas' markers
        }

        // The whole ensemble becomes unreachable for longer than a session: every ephemeral node expires.
        proxyOf.values().forEach(ZkProxy::cut);
        assertTrue(TestSupport.await(WAIT_MS, () -> oldLeader.state.demotions() == 1),
                "a primary that cannot reach ZooKeeper for a whole session steps down: " + describe(a, b, c));
        assertEquals(0, leaders(a, b, c));
        assertTrue(reply(oldLeader, "SET", "x", "1").startsWith("-CLUSTERDOWN"));
        assertEquals("v3", reply(oldLeader, "GET", "key-3").replace("\"", ""), "reads keep working during the outage");

        proxyOf.values().forEach(ZkProxy::heal);
        ClusterNode leader = awaitLeader(a, b, c);
        assertTrue(leader.state.epoch() >= 2);
        for (ClusterNode node : List.of(a, b, c)) {
            if (node != leader) {
                awaitFollowing(node, leader);
            }
        }
        for (ClusterNode node : List.of(a, b, c)) {
            for (int i = 0; i < 20; i++) {
                assertEquals("v" + i, node.get("key-" + i), node.id + " lost key-" + i);
            }
            assertEquals("x", node.get("marker-" + node.id), node.id + " did a full resync it did not need");
        }
        writeAndWaitForReplicas(leader, 2, "SET", "after-outage", "ok");
    }

    @Test
    void aPlannedFailoverIsAbortedAndNothingChangesWhenTheTargetHasNotCaughtUp() throws Exception {
        ClusterNode a = start("a");
        ClusterNode b = start("b");
        ClusterNode leader = awaitLeader(a, b);
        ClusterNode target = others(leader, a, b).getFirst();
        awaitFollowing(target, leader);
        writeAndWaitForReplicas(leader, 1, "SET", "k", "1");
        long epoch = leader.state.epoch();

        // The target's link breaks (it reconnects only after its backoff), and a write it will not have follows.
        leader.replicaManager.closeAll();
        assertEquals("+OK", reply(leader, "SET", "k", "2"));

        String refused = reply(leader, "FAILOVER", "TO", "127.0.0.1", Integer.toString(target.port), "TIMEOUT", "400");
        assertTrue(refused.startsWith("-ERR FAILOVER refused:"), refused);
        assertTrue(refused.contains("nothing was changed"), refused);

        assertTrue(leader.isLeader(), "the primary keeps its role");
        assertEquals(epoch, leader.state.epoch());
        assertEquals("+OK", reply(leader, "SET", "k", "3"), "and accepts writes again");
        awaitFollowing(target, leader);
        assertTrue(TestSupport.await(WAIT_MS, () -> "3".equals(target.get("k"))), "the target catches up afterwards");
    }

    @Test
    void aClusterNodeReplaysItsAppendOnlyFileAndServesItAfterBeingElected(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        Path aof = dir.resolve("node.aof");
        ClusterNode first = start(builder("first").aof(aof));
        assertTrue(TestSupport.await(WAIT_MS, first::isLeader));
        try (RespClient client = first.client()) {
            assertEquals("+OK", client.call("SET", "durable", "yes"));
            assertEquals(":1", client.call("INCR", "counter"));
        }
        first.shutdown();

        // A new process on the same data directory: it replays the file, then wins the election with it.
        ClusterNode second = start(builder("second").aof(aof));
        assertTrue(TestSupport.await(WAIT_MS, second::isLeader), describe(second));
        assertEquals("yes", second.get("durable"));
        assertEquals("1", second.get("counter"));
        assertEquals(2, second.state.epoch());
        try (RespClient client = second.client()) {
            assertEquals("\"yes\"", client.call("GET", "durable"));
            assertEquals(":2", client.call("INCR", "counter"));
        }
        assertTrue(second.aof.isActive(), "logging starts once the node is the primary");
    }

    @Test
    void loadBalancerEndpointsAndMetricsTellWhoMayWrite() throws Exception {
        ClusterNode a = start("a");
        ClusterNode b = start("b");
        ClusterNode leader = awaitLeader(a, b);
        ClusterNode follower = others(leader, a, b).getFirst();
        awaitFollowing(follower, leader);

        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        for (ClusterNode node : List.of(leader, follower)) {
            int metricsPort;
            try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
                metricsPort = socket.getLocalPort();
            }
            me.niteshh.redcake.metrics.MetricsRenderer renderer = new me.niteshh.redcake.metrics.MetricsRenderer(
                    node.serverStats, node.commandStats, node.stack.store(), node.replicationStats,
                    node.replicaManager, node.aof, node.memory, node.slowLog, node.state);
            me.niteshh.redcake.metrics.MetricsServer server = new me.niteshh.redcake.metrics.MetricsServer(
                    new me.niteshh.redcake.cli.CommandLineConfig(new org.springframework.boot.DefaultApplicationArguments(
                            "--metrics-port", Integer.toString(metricsPort))),
                    new me.niteshh.redcake.config.RedCakeServerConfig(node.port, "127.0.0.1"),
                    renderer, node.aof, node.replicationConfig, node.state);
            server.start();
            try {
                boolean isLeader = node == leader;
                assertEquals(isLeader ? 200 : 503, status(http, metricsPort, "/primary"), node.id + " /primary");
                assertEquals(isLeader ? 503 : 200, status(http, metricsPort, "/replica"), node.id + " /replica");
                assertEquals(200, status(http, metricsPort, "/health"));
                String metrics = http.send(java.net.http.HttpRequest.newBuilder(
                                java.net.URI.create("http://127.0.0.1:" + metricsPort + "/metrics")).build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString()).body();
                assertTrue(metrics.contains("redcake_cluster_enabled 1\n"), metrics);
                assertTrue(metrics.contains("redcake_cluster_is_primary " + (isLeader ? 1 : 0) + "\n"), metrics);
                assertTrue(metrics.contains("redcake_cluster_epoch 1\n"), metrics);
                assertTrue(metrics.contains("redcake_cluster_zk_connected 1\n"), metrics);
                assertTrue(metrics.contains("redcake_cluster_promotions_total " + (isLeader ? 1 : 0) + "\n"), metrics);
            } finally {
                server.stop();
            }
        }
    }

    private static int status(java.net.http.HttpClient http, int port, String path) throws Exception {
        return http.send(java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://127.0.0.1:" + port + path)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    void theClusterLogicDoesNotDependOnTheSignOrOriginOfNanoTime() throws Exception {
        // System.nanoTime() may be negative or start anywhere; only differences are meaningful.
        java.util.function.LongSupplier negativeOrigin = () -> System.nanoTime() - (1L << 62);
        ClusterNode a = start(builder("a").clock(negativeOrigin));
        ClusterNode b = start(builder("b").clock(negativeOrigin));

        ClusterNode leader = awaitLeader(a, b);
        ClusterNode follower = others(leader, a, b).getFirst();
        awaitFollowing(follower, leader);
        writeAndWaitForReplicas(leader, 1, "SET", "k", "v");

        leader.shutdown(); // hand-over, election and promotion with the odd clock
        assertTrue(TestSupport.await(WAIT_MS, follower::isLeader), describe(follower));
        assertEquals("v", follower.get("k"));
        assertTrue(follower.state.leaseRemainingMillis() > 0);
    }

    @Test
    void withDigestCredentialsOnlyTheClusterNodesCanReadOrChangeTheElectionState() throws Exception {
        ClusterNode a = start(builder("a").zkAuth("redcake:s3cret"));
        ClusterNode b = start(builder("b").zkAuth("redcake:s3cret"));
        ClusterNode leader = awaitLeader(a, b);
        awaitFollowing(others(leader, a, b).getFirst(), leader);

        // Another cluster on the same ZooKeeper, with other (here: no) credentials, is not locked out by this one.
        cluster = cluster + "-neighbour";
        ClusterNode neighbour = start("solo");
        assertTrue(TestSupport.await(WAIT_MS, neighbour::isLeader), describe(neighbour));
        cluster = cluster.replace("-neighbour", "");

        String leaderPath = "/redcake/" + cluster + "/leader";
        try (var anonymous = org.apache.curator.framework.CuratorFrameworkFactory.newClient(
                zookeeper.getConnectString(), new org.apache.curator.retry.RetryOneTime(100))) {
            anonymous.start();
            assertThrows(org.apache.zookeeper.KeeperException.NoAuthException.class,
                    () -> anonymous.getData().forPath(leaderPath), "an outsider must not read the election state");
            assertThrows(org.apache.zookeeper.KeeperException.NoAuthException.class,
                    () -> anonymous.delete().forPath(leaderPath), "nor depose the primary");
        }
        try (var member = org.apache.curator.framework.CuratorFrameworkFactory.builder()
                .connectString(zookeeper.getConnectString()).retryPolicy(new org.apache.curator.retry.RetryOneTime(100))
                .authorization("digest", "redcake:s3cret".getBytes()).build()) {
            member.start();
            assertTrue(new String(member.getData().forPath(leaderPath)).contains("node=" + leader.id));
        }
    }

    @Test
    void replicasStillQualifyWhenTheNewestLeaderDiedBeforeTheyFinishedSyncingWithIt(
            @org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        // All nodes persist their data. The first leader dies, and epoch 2 is granted to a successor that
        // dies at once, before any replica synced with it: the cluster epoch (2) is now ahead of what the
        // live replicas hold (epoch 1). They are running nodes, not stale disks, so they must still elect.
        ClusterNode a = start(ClusterNode.builder(proxy().connectString(), cluster, "a").aof(dir.resolve("a.aof")));
        proxyOf.put("a", proxies.getLast());
        ClusterNode b = start(ClusterNode.builder(proxy().connectString(), cluster, "b").aof(dir.resolve("b.aof")));
        proxyOf.put("b", proxies.getLast());
        ClusterNode c = start(ClusterNode.builder(proxy().connectString(), cluster, "c").aof(dir.resolve("c.aof")));
        proxyOf.put("c", proxies.getLast());
        ClusterNode leader = awaitLeader(a, b, c);
        List<ClusterNode> survivors = others(leader, a, b, c);
        for (ClusterNode follower : survivors) {
            awaitFollowing(follower, leader);
        }
        writeAndWaitForReplicas(leader, 2, "SET", "precious", "data");

        proxyOf.get(leader.id).cut();
        leader.crash();
        try (var curator = org.apache.curator.framework.CuratorFrameworkFactory.newClient(
                zookeeper.getConnectString(), new org.apache.curator.retry.RetryOneTime(100))) {
            curator.start();
            curator.setData().forPath("/redcake/" + cluster + "/epoch", "2\n".getBytes()); // the phantom epoch
        }

        ClusterNode successor = awaitLeader(survivors.toArray(new ClusterNode[0]));
        assertEquals(3, successor.state.epoch());
        assertEquals("data", successor.get("precious"));
    }

    @Test
    void aRollingRestartOfTheZooKeeperEnsembleDoesNotCauseAFailover() throws Exception {
        // A real three-member ensemble. Restarting its members one at a time (an everyday operation) keeps a
        // quorum, so sessions survive; the RedCake nodes may be fenced for a moment but must not fail over.
        List<InstanceSpec> specs = new ArrayList<>();
        for (int serverId = 1; serverId <= 3; serverId++) {
            specs.add(new InstanceSpec(null, -1, -1, -1, true, serverId, 200, -1));
        }
        try (org.apache.curator.test.TestingCluster ensemble = new org.apache.curator.test.TestingCluster(specs)) {
            ensemble.start();
            ClusterNode a = startOnEnsemble(ensemble.getConnectString(), "a");
            ClusterNode b = startOnEnsemble(ensemble.getConnectString(), "b");
            ClusterNode c = startOnEnsemble(ensemble.getConnectString(), "c");
            ClusterNode leader = awaitLeader(a, b, c);
            for (ClusterNode follower : others(leader, a, b, c)) {
                awaitFollowing(follower, leader);
            }
            writeAndWaitForReplicas(leader, 2, "SET", "before-roll", "1");

            for (InstanceSpec member : specs) {
                ensemble.killServer(member);
                Thread.sleep(600);
                ensemble.restartServer(member);
                Thread.sleep(1_500);
                assertTrue(TestSupport.await(WAIT_MS, () -> leaders(a, b, c) == 1),
                        "the primary must be writable again after ZooKeeper member " + member.getServerId()
                                + " restarted: " + describe(a, b, c));
            }

            assertSame(leader, awaitLeader(a, b, c), "the same node is still the primary");
            assertEquals(1, leader.state.epoch(), "no election took place");
            for (ClusterNode node : List.of(a, b, c)) {
                assertEquals(0, node.state.demotions(), node.id + " must not have given up the role");
            }
            writeAndWaitForReplicas(leader, 2, "SET", "after-roll", "1");
        }
    }
}
