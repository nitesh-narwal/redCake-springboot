package me.niteshh.redcake.command.commands.reads;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import me.niteshh.redcake.command.CommandException;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.config.MemoryConfig;
import me.niteshh.redcake.config.PersistenceConfig;
import me.niteshh.redcake.config.RedCakeServerConfig;
import me.niteshh.redcake.replication.ReplicationConfig;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.stats.SlowLog;
import me.niteshh.redcake.store.GlobMatcher;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@code CONFIG GET pattern [pattern ...]} and {@code CONFIG SET param value [...]}.
 *
 * <p>Readable: every parameter in {@link #getters}. Changeable at runtime:
 * {@code maxmemory}, {@code maxmemory-policy}, {@code slowlog-log-slower-than},
 * {@code slowlog-max-len} and {@code loglevel}. Everything else (ports, bind
 * address, TLS, persistence files) is fixed at startup and reports an error
 * when set. Secrets (API key, passwords) are deliberately not exposed.
 * Administrative command.
 */
@Component
public class ConfigCommand implements RedCakeCommand {

    private final Map<String, Supplier<String>> getters = new LinkedHashMap<>();
    private final Map<String, Consumer<String>> setters = new LinkedHashMap<>();

    public ConfigCommand(
            MemoryConfig memory,
            RedCakeServerConfig server,
            PersistenceConfig persistence,
            ReplicationConfig replication,
            SlowLog slowLog
    ) {
        getters.put("maxmemory", () -> Long.toString(memory.getMaxBytes()));
        getters.put("maxmemory-policy", () -> memory.getPolicy().configName());
        getters.put("slowlog-log-slower-than", () -> Long.toString(slowLog.getThresholdMicros()));
        getters.put("slowlog-max-len", () -> Integer.toString(slowLog.getMaxLength()));
        getters.put("loglevel", ConfigCommand::currentLogLevel);
        getters.put("port", () -> Integer.toString(server.getPort()));
        getters.put("bind", server::getBindAddress);
        getters.put("maxclients", () -> Integer.toString(server.getMaxClients()));
        getters.put("timeout", () -> Integer.toString(server.getIdleTimeoutSeconds()));
        getters.put("appendonly", () -> persistence.isEnabled() ? "yes" : "no");
        getters.put("appendfsync", () -> persistence.getFsync().name().toLowerCase(Locale.ROOT));
        getters.put("repl-backlog-size", () -> Integer.toString(replication.getBacklogBytes()));

        setters.put("maxmemory", value -> {
            try {
                memory.setMaxBytes(MemoryConfig.parseSize(value));
            } catch (IllegalArgumentException e) {
                throw new CommandException(e.getMessage());
            }
        });
        setters.put("maxmemory-policy", value -> {
            try {
                memory.setPolicy(MemoryConfig.Policy.parse(value));
            } catch (IllegalArgumentException e) {
                throw new CommandException(e.getMessage());
            }
        });
        setters.put("slowlog-log-slower-than", value -> slowLog.setThresholdMicros(CommandSupport.parseLong(value)));
        setters.put("slowlog-max-len", value -> slowLog.setMaxLength((int) CommandSupport.parseLong(value)));
        setters.put("loglevel", ConfigCommand::setLogLevel);
    }

    @Override
    public String name() {
        return "CONFIG";
    }

    @Override
    public boolean isAdmin() {
        return true;
    }

    @Override
    public RespValue execute(List<String> arguments) {
        if (arguments.isEmpty()) {
            return CommandSupport.wrongArity("config");
        }
        return switch (arguments.getFirst().toUpperCase(Locale.ROOT)) {
            case "GET" -> get(arguments.subList(1, arguments.size()));
            case "SET" -> set(arguments.subList(1, arguments.size()));
            default -> new ErrorValue("unknown subcommand '" + arguments.getFirst() + "' for 'config' command");
        };
    }

    private RespValue get(List<String> patterns) {
        if (patterns.isEmpty()) {
            return CommandSupport.wrongArity("config|get");
        }
        List<RespValue> out = new ArrayList<>();
        for (Map.Entry<String, Supplier<String>> entry : getters.entrySet()) {
            for (String pattern : patterns) {
                if (GlobMatcher.matches(pattern.toLowerCase(Locale.ROOT), entry.getKey())) {
                    out.add(new BulkString(entry.getKey()));
                    out.add(new BulkString(entry.getValue().get()));
                    break;
                }
            }
        }
        return new ArrayValue(out);
    }

    /** Validates every parameter first so a bad one cannot leave a half-applied change. */
    private RespValue set(List<String> pairs) {
        if (pairs.isEmpty() || pairs.size() % 2 != 0) {
            return CommandSupport.wrongArity("config|set");
        }
        for (int i = 0; i < pairs.size(); i += 2) {
            String param = pairs.get(i).toLowerCase(Locale.ROOT);
            if (!setters.containsKey(param)) {
                return new ErrorValue(getters.containsKey(param)
                        ? "CONFIG SET failed: '" + param + "' can only be set at startup"
                        : "Unknown option or number of arguments for CONFIG SET - '" + param + "'");
            }
        }
        for (int i = 0; i < pairs.size(); i += 2) {
            setters.get(pairs.get(i).toLowerCase(Locale.ROOT)).accept(pairs.get(i + 1));
        }
        return new SimpleString("OK");
    }

    private static String currentLogLevel() {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (factory instanceof LoggerContext context) {
            Level level = context.getLogger("me.niteshh.redcake").getEffectiveLevel();
            return level.toString().toLowerCase(Locale.ROOT);
        }
        return "unknown";
    }

    private static void setLogLevel(String value) {
        Level level = Level.toLevel(value, null);
        if (level == null) {
            throw new CommandException("loglevel must be one of: trace, debug, info, warn, error, off");
        }
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            context.getLogger("me.niteshh.redcake").setLevel(level);
        }
    }
}
