package me.niteshh.redcake.command.commands.reads;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.replication.ReplicationConfig;
import me.niteshh.redcake.replication.ReplicationRole;
import me.niteshh.redcake.replication.primary.ReplicaConnection;
import me.niteshh.redcake.replication.primary.ReplicaManager;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.stats.ReplicationStats;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code ROLE} - what this node is, in machine-readable form (used by
 * Sentinel-style tooling and client libraries):
 * <ul>
 *   <li>primary: {@code ["master", offset, [[ip, port, ackOffset], ...]]}</li>
 *   <li>replica: {@code ["slave", host, port, link-state, offset]}</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class RoleCommand implements RedCakeCommand {
    private final ReplicationConfig config;
    private final ReplicationStats stats;
    private final ReplicaManager replicaManager;

    @Override
    public String name() {
        return "ROLE";
    }

    @Override
    public RespValue execute(List<String> arguments) {
        if (!arguments.isEmpty()) {
            return CommandSupport.wrongArity("role");
        }

        if (config.getRole() == ReplicationRole.REPLICA) {
            return new ArrayValue(List.of(
                    new BulkString("slave"),
                    new BulkString(String.valueOf(config.getPrimaryHost())),
                    new IntegerValue(config.getPrimaryPort()),
                    new BulkString(stats.isMasterLinkUp() ? "connected" : "connect"),
                    new IntegerValue(stats.offset())));
        }

        List<RespValue> replicas = new ArrayList<>();
        for (ReplicaConnection replica : replicaManager.getReplicas()) {
            replicas.add(new ArrayValue(List.of(
                    new BulkString(replica.getRemoteHost()),
                    new BulkString(String.valueOf(replica.getListeningPort())),
                    new BulkString(String.valueOf(replica.getAckOffset())))));
        }
        return new ArrayValue(List.of(
                new BulkString("master"),
                new IntegerValue(stats.offset()),
                new ArrayValue(replicas)));
    }
}
