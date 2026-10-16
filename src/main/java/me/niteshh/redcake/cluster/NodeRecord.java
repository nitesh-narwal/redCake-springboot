package me.niteshh.redcake.cluster;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What a node publishes about itself in its ephemeral znode {@code /nodes/<nodeId>}. The election reads these
 * records to decide which replica has the best claim to become the next primary.
 *
 * @param nodeId      unique, stable name of the node
 * @param host        advertised address of the node's data port
 * @param port        advertised data port
 * @param priority    {@code replica-priority}: lower wins, 0 = never promote
 * @param role        what the node believes it is right now
 * @param replid      replication history the node's data belongs to
 * @param offset      replication stream offset the node has applied (or produced)
 * @param dataEpoch   the newest leader epoch whose complete dataset the node holds; {@code -1} = unknown/incomplete
 * @param durable     the node persists its data (AOF), so its {@code dataEpoch} survives a restart
 * @param restored    the node's data comes from its disk only: since this process started it has neither led nor
 *                    completed a sync. Only such data can be dangerously old; a live node's data tracks the stream
 * @param loading     the node is in the middle of a full resync: its data is incomplete
 * @param keys        number of keys held (tie-breaker for nodes that know nothing else, e.g. a cold start)
 * @param epoch       the leader epoch this node leads or follows ({@code -1} = none)
 * @param incarnation random id of this process run; tells a restarted node from its own ghost
 * @param updatedAtMillis wall-clock time of publication (informational)
 */
public record NodeRecord(
        String nodeId,
        String host,
        int port,
        int priority,
        Role role,
        String replid,
        long offset,
        long dataEpoch,
        boolean durable,
        boolean restored,
        boolean loading,
        long keys,
        long epoch,
        String incarnation,
        long updatedAtMillis
) {

    public enum Role {
        LEADER, FOLLOWER, CANDIDATE;

        String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Role fromWire(String text) {
            try {
                return valueOf(text.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return CANDIDATE;
            }
        }
    }

    public byte[] toBytes() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("node", nodeId);
        fields.put("host", host);
        fields.put("port", port);
        fields.put("priority", priority);
        fields.put("role", role.wire());
        fields.put("replid", replid);
        fields.put("offset", offset);
        fields.put("data-epoch", dataEpoch);
        fields.put("durable", durable ? 1 : 0);
        fields.put("restored", restored ? 1 : 0);
        fields.put("loading", loading ? 1 : 0);
        fields.put("keys", keys);
        fields.put("epoch", epoch);
        fields.put("incarnation", incarnation);
        fields.put("updated", updatedAtMillis);
        return RecordCodec.encode(fields);
    }

    /** @throws IllegalArgumentException if the data is not a valid record */
    public static NodeRecord parse(byte[] data) {
        Map<String, String> f = RecordCodec.decode(data);
        return new NodeRecord(
                RecordCodec.string(f, "node"),
                RecordCodec.string(f, "host"),
                (int) RecordCodec.number(f, "port", 0),
                (int) RecordCodec.number(f, "priority", ClusterConfig.DEFAULT_REPLICA_PRIORITY),
                Role.fromWire(RecordCodec.string(f, "role", "candidate")),
                RecordCodec.string(f, "replid", ""),
                RecordCodec.number(f, "offset", 0),
                RecordCodec.number(f, "data-epoch", -1),
                RecordCodec.bool(f, "durable"),
                RecordCodec.bool(f, "restored"),
                RecordCodec.bool(f, "loading"),
                RecordCodec.number(f, "keys", 0),
                RecordCodec.number(f, "epoch", -1),
                RecordCodec.string(f, "incarnation", ""),
                RecordCodec.number(f, "updated", 0));
    }

    /** {@code host:port} as shown in logs and {@code INFO}. */
    public String address() {
        return host + ":" + port;
    }

    /** Same content ignoring the replication offset and the publication time (which change constantly). */
    public boolean sameStateAs(NodeRecord other) {
        return other != null
                && nodeId.equals(other.nodeId) && host.equals(other.host) && port == other.port
                && priority == other.priority && role == other.role && replid.equals(other.replid)
                && dataEpoch == other.dataEpoch && durable == other.durable && restored == other.restored
                && loading == other.loading
                && epoch == other.epoch && incarnation.equals(other.incarnation);
    }
}
