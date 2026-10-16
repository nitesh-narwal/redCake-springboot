package me.niteshh.redcake.cluster;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** The election rules as a pure function: who may lead, and who goes first. */
class ElectionPolicyTest {

    /** Mutable builder so each test only states what is special about its nodes. */
    private static final class Node {
        String id;
        int priority = 100;
        NodeRecord.Role role = NodeRecord.Role.FOLLOWER;
        long offset;
        long dataEpoch = 1;
        boolean durable;
        boolean restored;
        boolean loading;
        long keys;

        Node(String id) {
            this.id = id;
        }

        Node priority(int value) {
            priority = value;
            return this;
        }

        Node offset(long value) {
            offset = value;
            return this;
        }

        Node dataEpoch(long value) {
            dataEpoch = value;
            return this;
        }

        Node durable() {
            durable = true;
            return this;
        }

        /** Data that only came from disk: the node has not synced or led since it started. */
        Node restored() {
            restored = true;
            return this;
        }

        Node loading() {
            loading = true;
            return this;
        }

        Node role(NodeRecord.Role value) {
            role = value;
            return this;
        }

        Node keys(long value) {
            keys = value;
            return this;
        }

        NodeRecord build() {
            return new NodeRecord(id, "10.0.0." + id.length(), 6379, priority, role, "replid", offset, dataEpoch,
                    durable, restored, loading, keys, 1, "inc-" + id, 0);
        }
    }

    private static Node node(String id) {
        return new Node(id);
    }

    private static List<String> order(ElectionPolicy.Ranking ranking) {
        return ranking.eligible().stream().map(NodeRecord::nodeId).toList();
    }

    private static ElectionPolicy.Ranking rank(long clusterEpoch, String preferred, Node... nodes) {
        return ElectionPolicy.rank(Stream.of(nodes).map(Node::build).toList(), clusterEpoch, preferred);
    }

    @Test
    void theMostUpToDateReplicaWins() {
        ElectionPolicy.Ranking ranking = rank(1, null,
                node("a").offset(100), node("b").offset(900), node("c").offset(500));

        assertEquals(List.of("b", "c", "a"), order(ranking));
        assertEquals(0, ranking.rankOf("b"));
        assertEquals(2, ranking.rankOf("a"));
        assertEquals(-1, ranking.rankOf("nobody"));
    }

    @Test
    void lowerPriorityNumberBeatsAHigherOffsetLikeRedisSentinel() {
        ElectionPolicy.Ranking ranking = rank(1, null,
                node("fast").offset(1000).priority(100), node("preferred").offset(10).priority(10));

        assertEquals(List.of("preferred", "fast"), order(ranking));
    }

    @Test
    void priorityZeroNeverLeads() {
        ElectionPolicy.Ranking ranking = rank(1, null, node("dr-site").priority(0).offset(9999), node("b"));

        assertEquals(List.of("b"), order(ranking));
        assertTrue(ranking.excluded().get("dr-site").contains("priority"));
    }

    @Test
    void anOperatorMayHandOverToAPriorityZeroNodeExplicitly() {
        ElectionPolicy.Ranking ranking = rank(1, "dr-site", node("dr-site").priority(0), node("b").offset(50));

        assertEquals(List.of("dr-site", "b"), order(ranking));
    }

    @Test
    void aNodeInTheMiddleOfAFullResyncIsNeverPromoted() {
        ElectionPolicy.Ranking ranking = rank(1, null, node("loading").loading().offset(5000), node("ok").offset(1));

        assertEquals(List.of("ok"), order(ranking));
        assertTrue(ranking.excluded().get("loading").contains("full resync"));
    }

    @Test
    void theFailedLeadersOwnLingeringRecordDoesNotCompete() {
        ElectionPolicy.Ranking ranking = rank(1, null,
                node("old-leader").role(NodeRecord.Role.LEADER).offset(7000), node("b").offset(10));

        assertEquals(List.of("b"), order(ranking));
    }

    @Test
    void newerDataLineageBeatsAHigherOffsetOfAnOlderOne() {
        // Epoch 3's leader started from epoch 2's stream, so offsets only compare within the same lineage.
        ElectionPolicy.Ranking ranking = rank(3, null,
                node("old").dataEpoch(2).offset(9000), node("current").dataEpoch(3).offset(100));

        assertEquals(List.of("current", "old"), order(ranking));
    }

    @Test
    void aNodeRestoredFromAnOlderAofThanTheLastLeaderIsExcluded() {
        ElectionPolicy.Ranking ranking = rank(5, null,
                node("stale-aof").durable().restored().dataEpoch(3).offset(0),
                node("fresh-aof").durable().restored().dataEpoch(5));

        assertEquals(List.of("fresh-aof"), order(ranking));
        assertTrue(ranking.excluded().get("stale-aof").contains("older"), ranking.summary());
    }

    @Test
    void aNodeWithNoDurableDataCannotTakeOverAnEstablishedCluster() {
        // Brand new disk: dataEpoch is unknown (-1) although the cluster is at epoch 4.
        ElectionPolicy.Ranking ranking = rank(4, null, node("empty-disk").durable().restored().dataEpoch(-1));

        assertTrue(ranking.eligible().isEmpty());
        assertTrue(ranking.best().isEmpty());
    }

    @Test
    void aRunningNodeOneEpochBehindStillQualifiesWhenTheNewestLeaderDiesBeforeItSynced() {
        // Epoch 3's leader died moments after its election: the replicas had followed epoch 2 and had not yet
        // finished syncing with epoch 3. They are live nodes, not stale disks - they must be able to elect.
        ElectionPolicy.Ranking ranking = rank(3, null,
                node("a").durable().dataEpoch(2).offset(900), node("b").durable().dataEpoch(2).offset(950));

        assertEquals(List.of("b", "a"), order(ranking), "the more advanced one first");
    }

    @Test
    void anyDurableNodeMayLeadAFreshCluster() {
        ElectionPolicy.Ranking ranking = rank(0, null,
                node("a").durable().restored().dataEpoch(-1), node("b").durable().restored().dataEpoch(-1));

        assertEquals(2, ranking.eligible().size());
    }

    @Test
    void anInMemoryNodeMayLeadAfterATotalOutageBecauseThereIsNothingLeftToProtect() {
        ElectionPolicy.Ranking ranking = rank(9, null, node("a").dataEpoch(-1), node("b").dataEpoch(-1));

        assertEquals(List.of("a", "b"), order(ranking));
    }

    @Test
    void coldStartPrefersTheNodeThatHoldsData() {
        ElectionPolicy.Ranking ranking = rank(0, null,
                node("aaa-empty").dataEpoch(-1).keys(0), node("zzz-has-data").dataEpoch(-1).keys(40_000));

        assertEquals("zzz-has-data", ranking.best().orElseThrow().nodeId());
    }

    @Test
    void ifNothingElseDiffersTheNodeIdDecidesSoEveryNodeAgrees() {
        ElectionPolicy.Ranking forward = rank(1, null, node("n1"), node("n2"), node("n3"));
        ElectionPolicy.Ranking backward = rank(1, null, node("n3"), node("n2"), node("n1"));

        assertEquals(order(forward), order(backward));
        assertEquals(List.of("n1", "n2", "n3"), order(forward));
    }

    @Test
    void theHandOverTargetGoesFirstEvenWithALowerOffset() {
        ElectionPolicy.Ranking ranking = rank(1, "slow", node("fast").offset(900), node("slow").offset(100));

        assertEquals("slow", ranking.best().orElseThrow().nodeId());
    }

    @Test
    void aHandOverTargetStillHasToHoldCompleteData() {
        ElectionPolicy.Ranking ranking = rank(1, "slow", node("fast").offset(900), node("slow").loading());

        assertEquals(List.of("fast"), order(ranking));
    }

    @Test
    void theSummaryNamesCandidatesAndTheReasonsOthersAreOut() {
        ElectionPolicy.Ranking ranking = rank(1, null,
                node("b").offset(5), node("zero").priority(0));

        String summary = ranking.summary();
        assertTrue(summary.contains("b(offset 5)"), summary);
        assertTrue(summary.contains("zero (replica-priority is 0)"), summary);
        assertEquals("candidates=none", rank(1, null).summary());
    }
}
