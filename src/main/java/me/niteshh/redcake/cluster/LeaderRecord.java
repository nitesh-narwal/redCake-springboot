package me.niteshh.redcake.cluster;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Content of the ephemeral znode {@code /leader}: the node that currently owns the primary role. The znode
 * exists exactly as long as the owner's ZooKeeper session, which is what makes the election safe.
 *
 * @param epoch       fencing token: strictly increases with every change of leader
 * @param incarnation random id of the owner's process run (a restarted owner has a different one)
 */
public record LeaderRecord(
        String nodeId,
        String host,
        int port,
        long epoch,
        String incarnation,
        long sinceMillis
) {

    public byte[] toBytes() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("node", nodeId);
        fields.put("host", host);
        fields.put("port", port);
        fields.put("epoch", epoch);
        fields.put("incarnation", incarnation);
        fields.put("since", sinceMillis);
        return RecordCodec.encode(fields);
    }

    /** @throws IllegalArgumentException if the data is not a valid record */
    public static LeaderRecord parse(byte[] data) {
        Map<String, String> f = RecordCodec.decode(data);
        return new LeaderRecord(
                RecordCodec.string(f, "node"),
                RecordCodec.string(f, "host"),
                (int) RecordCodec.number(f, "port", 0),
                RecordCodec.number(f, "epoch", 0),
                RecordCodec.string(f, "incarnation", ""),
                RecordCodec.number(f, "since", 0));
    }

    public String address() {
        return host + ":" + port;
    }
}
