package me.niteshh.redcake.cluster;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Written by a primary that hands over on purpose ({@code FAILOVER}, graceful shutdown) just before it releases
 * the leader znode: "{@code target} is fully caught up, let it win". The znode is ephemeral, so the hint
 * disappears with the old primary's session; {@code expiresAtMillis} bounds it even if that session lingers.
 *
 * @param target     node id that should take over, or empty if any caught-up replica will do
 * @param fromEpoch  epoch of the primary that is stepping down
 */
public record FailoverHint(String target, long fromEpoch, long expiresAtMillis) {

    public byte[] toBytes() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("target", target);
        fields.put("from-epoch", fromEpoch);
        fields.put("expires", expiresAtMillis);
        return RecordCodec.encode(fields);
    }

    public static FailoverHint parse(byte[] data) {
        Map<String, String> f = RecordCodec.decode(data);
        return new FailoverHint(
                RecordCodec.string(f, "target", ""),
                RecordCodec.number(f, "from-epoch", 0),
                RecordCodec.number(f, "expires", 0));
    }

    public boolean isExpired(long nowMillis) {
        return nowMillis >= expiresAtMillis;
    }

    public boolean hasTarget() {
        return !target.isEmpty();
    }
}
