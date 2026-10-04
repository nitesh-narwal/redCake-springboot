package me.niteshh.redcake.command.commands.reads;

import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.replication.ReplicationConfig;
import me.niteshh.redcake.resp.BulkString;
import me.niteshh.redcake.resp.ErrorValue;
import me.niteshh.redcake.resp.RespValue;
import me.niteshh.redcake.store.KeyValueStore;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class InfoCommand implements RedCakeCommand {

    private static final String VERSION = "0.0.1";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final KeyValueStore store;
    private final ReplicationConfig replicationConfig;
    private final String masterReplid = createMasterReplid();

    public InfoCommand(
            KeyValueStore store,
            ReplicationConfig replicationConfig
    ) {
        this.store = store;
        this.replicationConfig = replicationConfig;
    }

    @Override
    public String name() {
        return "INFO";
    }

    @Override
    public RespValue execute(List<String> arguments) {
        if (arguments.size() > 1) {
            return new ErrorValue(
                    "wrong number of arguments for 'info' command"
            );
        }

        String section = arguments.isEmpty()
                ? "all"
                : arguments.getFirst().toLowerCase(Locale.ROOT);

        String response = switch (section) {
            case "all", "default" -> allInfo();
            case "server" -> serverInfo();
            case "replication" -> replicationInfo();
            case "keyspace" -> keyspaceInfo();
            default -> "";
        };

        return new BulkString(response);
    }

    private String allInfo() {
        return serverInfo()
                + replicationInfo()
                + keyspaceInfo();
    }

    private String serverInfo() {
        return "# Server\r\n"
                + "redcake_version:" + VERSION + "\r\n"
                + "os:" + System.getProperty("os.name") + "\r\n"
                + "java_version:" + System.getProperty("java.version") + "\r\n"
                + "\r\n";
    }

    private String replicationInfo() {
        return "# Replication\r\n"
                + "role:"
                + replicationConfig.getRole().name().toLowerCase(Locale.ROOT)
                + "\r\n"
                + "master_replid:" + masterReplid + "\r\n"
                + "master_repl_offset:0\r\n\r\n";
    }

    private static String createMasterReplid() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        StringBuilder replid = new StringBuilder(40);
        for (byte value : bytes) {
            replid.append(String.format("%02x", value & 0xff));
        }
        return replid.toString();
    }

    private String keyspaceInfo() {
        AtomicInteger keyCount = new AtomicInteger();
        store.forEachSnapshot(ignored -> keyCount.incrementAndGet());

        return "# Keyspace\r\n"
                + "db0:keys=" + keyCount + "\r\n\r\n";
    }
}
