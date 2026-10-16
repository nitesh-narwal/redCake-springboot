package me.niteshh.redcake.cluster;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Decides which nodes may become the next primary and in which order. A pure function of the published
 * {@link NodeRecord}s, so every node computes the same ranking and the rules can be unit-tested without
 * ZooKeeper.
 *
 * <h3>Who is eligible</h3>
 * <ul>
 *   <li>Not the failed leader itself (its record may outlive its leader znode for a moment).</li>
 *   <li>Not in the middle of a full resync - its dataset is incomplete.</li>
 *   <li>Not {@code replica-priority 0} (an operator's "never promote this one"), unless an operator
 *       explicitly hands over to it.</li>
 *   <li>A node that persists its data must not hold data <em>older</em> than the last leader's epoch: a
 *       replica with a month-old append-only file must never take over a cluster that has moved on.</li>
 * </ul>
 *
 * <h3>Order among the eligible</h3>
 * explicit handover target, then lowest priority number, then the newest data lineage, then the highest
 * replication offset (least data lost), then the most keys (cold start: prefer the node that has data),
 * then the node id (so all nodes agree).
 */
public final class ElectionPolicy {

    private ElectionPolicy() {
    }

    /**
     * @param eligible candidates, best first
     * @param excluded node id to the reason it may not be promoted
     */
    public record Ranking(List<NodeRecord> eligible, Map<String, String> excluded) {

        /** @return 0 for the best candidate, or -1 if the node is not eligible */
        public int rankOf(String nodeId) {
            for (int i = 0; i < eligible.size(); i++) {
                if (eligible.get(i).nodeId().equals(nodeId)) {
                    return i;
                }
            }
            return -1;
        }

        public Optional<NodeRecord> best() {
            return eligible.isEmpty() ? Optional.empty() : Optional.of(eligible.getFirst());
        }

        /** One line for {@code INFO} and logs. */
        public String summary() {
            StringBuilder text = new StringBuilder("candidates=");
            if (eligible.isEmpty()) {
                text.append("none");
            }
            for (int i = 0; i < eligible.size(); i++) {
                NodeRecord node = eligible.get(i);
                text.append(i == 0 ? "" : ",").append(node.nodeId())
                        .append("(offset ").append(node.offset()).append(')');
            }
            if (!excluded.isEmpty()) {
                text.append("; excluded: ");
                excluded.forEach((id, reason) -> text.append(id).append(" (").append(reason).append(") "));
            }
            return text.toString().strip();
        }
    }

    /**
     * @param members      every registered node, including the one that is asking
     * @param clusterEpoch highest leader epoch ever granted ({@code 0} = the cluster never had a leader)
     * @param preferred    node id chosen by an operator-initiated handover, or {@code null}
     */
    public static Ranking rank(Collection<NodeRecord> members, long clusterEpoch, String preferred) {
        List<NodeRecord> eligible = new ArrayList<>();
        Map<String, String> excluded = new LinkedHashMap<>();

        for (NodeRecord node : members) {
            String reason = exclusionReason(node, clusterEpoch, node.nodeId().equals(preferred));
            if (reason == null) {
                eligible.add(node);
            } else {
                excluded.put(node.nodeId(), reason);
            }
        }

        Comparator<NodeRecord> order = Comparator
                .comparing((NodeRecord n) -> !n.nodeId().equals(preferred))
                .thenComparingInt(NodeRecord::priority)
                .thenComparing(Comparator.comparingLong(NodeRecord::dataEpoch).reversed())
                .thenComparing(Comparator.comparingLong(NodeRecord::offset).reversed())
                .thenComparing(Comparator.comparingLong(NodeRecord::keys).reversed())
                .thenComparing(NodeRecord::nodeId);
        eligible.sort(order);
        return new Ranking(List.copyOf(eligible), excluded);
    }

    /** @return why the node may not be promoted, or {@code null} if it may */
    static String exclusionReason(NodeRecord node, long clusterEpoch, boolean chosenByOperator) {
        if (node.role() == NodeRecord.Role.LEADER) {
            return "still registered as the failed leader";
        }
        if (node.loading()) {
            return "still loading a full resync";
        }
        if (node.priority() == 0 && !chosenByOperator) {
            return "replica-priority is 0";
        }
        if (node.durable() && node.restored() && clusterEpoch > 0 && node.dataEpoch() < clusterEpoch) {
            return "its data (epoch " + node.dataEpoch() + ") is older than the last leader's epoch "
                    + clusterEpoch;
        }
        return null;
    }
}
