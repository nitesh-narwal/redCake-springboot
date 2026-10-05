package me.niteshh.redcake.cli;

import lombok.Getter;
import me.niteshh.redcake.config.AclConfig;
import me.niteshh.redcake.config.MemoryConfig;
import me.niteshh.redcake.config.PersistenceConfig;
import me.niteshh.redcake.config.RedCakeServerConfig;
import me.niteshh.redcake.config.TlsConfig;
import me.niteshh.redcake.replication.ReplicationConfig;
import me.niteshh.redcake.replication.ReplicationRole;
import me.niteshh.redcake.security.AclRegistry;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses and validates the command line, an optional config file and the
 * secrets in environment variables into immutable configuration objects.
 *
 * <p>Parsing is strict on purpose: unknown options, missing values, duplicate
 * options and out-of-range numbers abort startup with a clear message instead
 * of silently falling back to defaults.
 *
 * <h3>Config file ({@code --config redcake.conf})</h3>
 * One option per line, the {@code --} omitted: {@code port 6380},
 * {@code replicaof 10.0.0.5 6379}, {@code allow-insecure yes}. {@code #}
 * starts a comment. <b>Command-line options override the file.</b>
 *
 * <h3>Secrets</h3>
 * API key: {@code REDCAKE_API_KEY}, {@code --api-key-file}, or (discouraged,
 * visible in {@code ps}) {@code --api-key}. TLS store passwords:
 * {@code REDCAKE_TLS_KEYSTORE_PASSWORD}/{@code REDCAKE_TLS_TRUSTSTORE_PASSWORD}
 * or the matching {@code ...-password-file} option.
 *
 * <p>Run {@code java -jar redCake.jar --help} for the option list.
 */
@Getter
@Component
public final class CommandLineConfig {

    private static final int DEFAULT_PORT = 6379;

    /** Number of values each option takes; also the set of valid options. */
    private static final Map<String, Integer> ARITY = Map.ofEntries(
            Map.entry("--port", 1), Map.entry("--bind", 1), Map.entry("--replicaof", 2),
            Map.entry("--api-key", 1), Map.entry("--api-key-file", 1),
            Map.entry("--max-clients", 1), Map.entry("--timeout", 1), Map.entry("--allow-insecure", 0),
            Map.entry("--aof-file", 1), Map.entry("--aof-fsync", 1),
            Map.entry("--maxmemory", 1), Map.entry("--maxmemory-policy", 1),
            Map.entry("--tls-keystore", 1), Map.entry("--tls-keystore-password-file", 1),
            Map.entry("--tls-truststore", 1), Map.entry("--tls-truststore-password-file", 1),
            Map.entry("--tls-client-auth", 0), Map.entry("--tls-replication", 0),
            Map.entry("--acl-file", 1), Map.entry("--hash-password", 0),
            Map.entry("--metrics-port", 1), Map.entry("--slowlog-threshold-us", 1),
            Map.entry("--repl-backlog-size", 1),
            Map.entry("--config", 1), Map.entry("--help", 0), Map.entry("-h", 0)
    );

    private final RedCakeServerConfig serverConfig;
    private final ReplicationConfig replicationConfig;
    private final PersistenceConfig persistenceConfig;
    private final MemoryConfig memoryConfig;
    private final TlsConfig tlsConfig;
    private final AclConfig aclConfig;
    private final String apiKey;
    private final int metricsPort;
    private final long slowlogThresholdMicros;

    public CommandLineConfig(ApplicationArguments arguments) {
        Options o = parse(expandConfigFile(arguments.getSourceArgs()));

        this.serverConfig = new RedCakeServerConfig(
                o.port, o.bindAddress, o.maxClients, o.timeoutSeconds, o.allowInsecure);
        this.replicationConfig = new ReplicationConfig(
                o.role, o.primaryHost, o.primaryPort, o.backlogBytes);
        this.persistenceConfig = new PersistenceConfig(o.aofFile, o.aofFsync);
        this.memoryConfig = new MemoryConfig(o.maxMemory, o.maxMemoryPolicy);
        this.tlsConfig = new TlsConfig(
                o.tlsKeystore, o.tlsKeystorePassword, o.tlsTruststore, o.tlsTruststorePassword,
                o.tlsClientAuth, o.tlsReplication);
        this.aclConfig = new AclConfig(o.aclFile);
        this.apiKey = o.apiKey;
        this.metricsPort = o.metricsPort;
        this.slowlogThresholdMicros = o.slowlogThresholdMicros;
    }

    /** Mutable holder filled while parsing; turned into the immutable config objects above. */
    private static final class Options {
        int port = DEFAULT_PORT;
        String bindAddress = RedCakeServerConfig.DEFAULT_BIND_ADDRESS;
        String apiKey = System.getenv("REDCAKE_API_KEY");
        int maxClients = RedCakeServerConfig.DEFAULT_MAX_CLIENTS;
        int timeoutSeconds;
        boolean allowInsecure;
        Path aofFile;
        PersistenceConfig.Fsync aofFsync = PersistenceConfig.Fsync.EVERYSEC;
        ReplicationRole role = ReplicationRole.PRIMARY;
        String primaryHost;
        int primaryPort = DEFAULT_PORT;
        int backlogBytes = ReplicationConfig.DEFAULT_BACKLOG_BYTES;
        long maxMemory;
        MemoryConfig.Policy maxMemoryPolicy = MemoryConfig.Policy.NOEVICTION;
        Path tlsKeystore;
        char[] tlsKeystorePassword = envChars("REDCAKE_TLS_KEYSTORE_PASSWORD");
        Path tlsTruststore;
        char[] tlsTruststorePassword = envChars("REDCAKE_TLS_TRUSTSTORE_PASSWORD");
        boolean tlsClientAuth;
        boolean tlsReplication;
        Path aclFile;
        int metricsPort;
        long slowlogThresholdMicros = 10_000;
    }

    private Options parse(String[] args) {
        Options o = new Options();
        boolean portSpecified = false;
        boolean replicaOfSpecified = false;

        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            if (!ARITY.containsKey(argument)) {
                throw new IllegalArgumentException("Unknown argument: " + argument);
            }

            switch (argument) {
                case "--port" -> {
                    if (portSpecified) {
                        throw new IllegalArgumentException("--port may only be specified once");
                    }
                    portSpecified = true;
                    o.port = parsePort(requireValue(args, i++, "--port"), "--port");
                }
                case "--bind" -> {
                    o.bindAddress = requireValue(args, i++, "--bind");
                    if (o.bindAddress.isBlank()) {
                        throw new IllegalArgumentException("--bind requires a non-empty address");
                    }
                }
                case "--api-key" -> {
                    o.apiKey = requireValue(args, i++, "--api-key");
                    if (o.apiKey.isBlank()) {
                        throw new IllegalArgumentException("--api-key must not be empty");
                    }
                }
                case "--api-key-file" -> o.apiKey = new String(
                        readSecretFile(requireValue(args, i++, "--api-key-file"), "--api-key-file"));
                case "--max-clients" -> o.maxClients = parseInt(
                        requireValue(args, i++, "--max-clients"), "--max-clients", 1);
                case "--timeout" -> o.timeoutSeconds = parseInt(
                        requireValue(args, i++, "--timeout"), "--timeout", 0);
                case "--allow-insecure" -> o.allowInsecure = true;
                case "--aof-file" -> {
                    String file = requireValue(args, i++, "--aof-file");
                    if (file.isBlank()) {
                        throw new IllegalArgumentException("--aof-file must not be empty");
                    }
                    o.aofFile = Path.of(file);
                }
                case "--aof-fsync" -> {
                    String mode = requireValue(args, i++, "--aof-fsync");
                    try {
                        o.aofFsync = PersistenceConfig.Fsync.valueOf(mode.toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("--aof-fsync must be one of: always, everysec, no");
                    }
                }
                case "--maxmemory" -> o.maxMemory = parseSize(requireValue(args, i++, "--maxmemory"), "--maxmemory");
                case "--maxmemory-policy" -> o.maxMemoryPolicy =
                        MemoryConfig.Policy.parse(requireValue(args, i++, "--maxmemory-policy"));
                case "--repl-backlog-size" -> o.backlogBytes = (int) Math.min(
                        Integer.MAX_VALUE,
                        parseSize(requireValue(args, i++, "--repl-backlog-size"), "--repl-backlog-size"));
                case "--tls-keystore" -> o.tlsKeystore = Path.of(requireValue(args, i++, "--tls-keystore"));
                case "--tls-keystore-password-file" -> o.tlsKeystorePassword = readSecretFile(
                        requireValue(args, i++, "--tls-keystore-password-file"), "--tls-keystore-password-file");
                case "--tls-truststore" -> o.tlsTruststore = Path.of(requireValue(args, i++, "--tls-truststore"));
                case "--tls-truststore-password-file" -> o.tlsTruststorePassword = readSecretFile(
                        requireValue(args, i++, "--tls-truststore-password-file"), "--tls-truststore-password-file");
                case "--tls-client-auth" -> o.tlsClientAuth = true;
                case "--tls-replication" -> o.tlsReplication = true;
                case "--acl-file" -> o.aclFile = Path.of(requireValue(args, i++, "--acl-file"));
                case "--metrics-port" -> o.metricsPort = parsePort(
                        requireValue(args, i++, "--metrics-port"), "--metrics-port");
                case "--slowlog-threshold-us" -> {
                    try {
                        o.slowlogThresholdMicros = Long.parseLong(requireValue(args, i++, "--slowlog-threshold-us"));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("--slowlog-threshold-us requires a number");
                    }
                }
                case "--replicaof" -> {
                    if (replicaOfSpecified) {
                        throw new IllegalArgumentException("--replicaof may only be specified once");
                    }
                    if (i + 2 >= args.length) {
                        throw new IllegalArgumentException("--replicaof requires <host> <port>");
                    }
                    replicaOfSpecified = true;
                    o.primaryHost = args[++i];
                    if (o.primaryHost.isBlank()) {
                        throw new IllegalArgumentException("Primary host must not be empty");
                    }
                    o.primaryPort = parsePort(args[++i], "--replicaof");
                    o.role = ReplicationRole.REPLICA;
                }
                case "--hash-password" -> {
                    printPasswordHash();
                    throw new SystemExitException();
                }
                case "--config" -> i++; // already expanded; skip its value
                case "--help", "-h" -> {
                    printUsage();
                    throw new SystemExitException();
                }
                default -> throw new IllegalArgumentException("Unknown argument: " + argument);
            }
        }
        return o;
    }

    // ---------------------------------------------------------- config file

    /**
     * Replaces {@code --config <file>} by the file's options. Options the
     * command line also sets are dropped from the file part (CLI wins).
     */
    private String[] expandConfigFile(String[] args) {
        int configIndex = -1;
        for (int i = 0; i < args.length; i++) {
            if ("--config".equals(args[i])) {
                configIndex = i;
                break;
            }
            Integer arity = ARITY.get(args[i]);
            i += arity == null ? 0 : arity;
        }
        if (configIndex < 0) {
            return args;
        }

        String file = requireValue(args, configIndex, "--config");
        List<String> cli = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (i == configIndex) {
                i++; // skip "--config <file>"
            } else {
                cli.add(args[i]);
            }
        }

        List<String> expanded = new ArrayList<>(fileOptions(Path.of(file), cli));
        expanded.addAll(cli);
        return expanded.toArray(new String[0]);
    }

    /** Reads {@code key value...} lines and converts them to options, skipping those overridden by the CLI. */
    private List<String> fileOptions(Path file, List<String> cli) {
        List<String> overridden = new ArrayList<>();
        for (int i = 0; i < cli.size(); i++) {
            overridden.add(cli.get(i));
            Integer arity = ARITY.get(cli.get(i));
            i += arity == null ? 0 : arity;
        }

        List<String> out = new ArrayList<>();
        try {
            int lineNumber = 0;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                lineNumber++;
                String text = line.strip();
                if (text.isEmpty() || text.startsWith("#")) {
                    continue;
                }
                String[] words = text.split("\\s+");
                String option = "--" + words[0];
                Integer arity = ARITY.get(option);
                if (arity == null || "--config".equals(option)) {
                    throw new IllegalArgumentException(file + ":" + lineNumber + ": unknown option '" + words[0] + "'");
                }
                if (overridden.contains(option)) {
                    continue;
                }
                if (arity == 0) { // boolean flag: "yes"/"no"
                    String flag = words.length > 1 ? words[1].toLowerCase(Locale.ROOT) : "yes";
                    if (flag.equals("yes") || flag.equals("true")) {
                        out.add(option);
                    } else if (!flag.equals("no") && !flag.equals("false")) {
                        throw new IllegalArgumentException(file + ":" + lineNumber + ": expected yes or no");
                    }
                } else {
                    if (words.length != arity + 1) {
                        throw new IllegalArgumentException(
                                file + ":" + lineNumber + ": '" + words[0] + "' needs " + arity + " value(s)");
                    }
                    out.add(option);
                    for (int w = 1; w < words.length; w++) {
                        out.add(words[w]);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read --config file " + file + ": " + e.getMessage(), e);
        }
        return out;
    }

    // ---------------------------------------------------------------- helpers

    /** Returns the value following option {@code args[index]} or fails with a clear message. */
    private String requireValue(String[] args, int index, String option) {
        if (index + 1 >= args.length) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        return args[index + 1];
    }

    private char[] readSecretFile(String path, String option) {
        try {
            String secret = Files.readString(Path.of(path), StandardCharsets.UTF_8).strip();
            if (secret.isEmpty()) {
                throw new IllegalArgumentException(option + " file is empty: " + path);
            }
            return secret.toCharArray();
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read " + option + ": " + path, e);
        }
    }

    private static char[] envChars(String name) {
        String value = System.getenv(name);
        return value == null ? null : value.toCharArray();
    }

    private int parseInt(String value, String option, int minimum) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum) {
                throw new IllegalArgumentException(option + " must be at least " + minimum);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + " requires a number: " + value);
        }
    }

    private long parseSize(String value, String option) {
        try {
            return MemoryConfig.parseSize(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(option + " requires a size such as 1048576, 512k, 100mb or 2g: " + value);
        }
    }

    private int parsePort(String value, String option) {
        final int port;
        try {
            port = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + " requires a valid port: " + value);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
        return port;
    }

    /** {@code --hash-password}: reads a password from stdin and prints its SHA-256 for the ACL file. */
    private void printPasswordHash() {
        try {
            String password = new String(System.in.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("[\\r\\n]+$", "");
            System.out.println(AclRegistry.hash(password.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read the password from standard input", e);
        }
    }

    private void printUsage() {
        System.out.println("""

                RedCake usage:
                  java -jar redCake.jar [options]

                Network & clients
                  --port N                 listen port (default 6379)
                  --bind ADDR              listen address (default 127.0.0.1)
                  --max-clients N          max concurrent clients (default 10000)
                  --timeout SECONDS        close idle clients (default 0 = never)

                Security
                  --api-key KEY            require AUTH (prefer REDCAKE_API_KEY / --api-key-file)
                  --api-key-file PATH      read the API key from a file
                  --acl-file PATH          named users with roles (see AclRegistry)
                  --hash-password          read a password on stdin, print its SHA-256 for --acl-file
                  --allow-insecure         permit non-loopback bind without credentials
                  --tls-keystore PATH      enable TLS (PKCS12/JKS with key + certificate)
                  --tls-keystore-password-file PATH   (or env REDCAKE_TLS_KEYSTORE_PASSWORD)
                  --tls-truststore PATH    trusted certificates (client auth / replication)
                  --tls-truststore-password-file PATH (or env REDCAKE_TLS_TRUSTSTORE_PASSWORD)
                  --tls-client-auth        require client certificates (mutual TLS)
                  --tls-replication        use TLS when this node replicates from its primary

                Replication
                  --replicaof HOST PORT    run as a read-only replica
                  --repl-backlog-size SIZE partial-resync buffer (default 1mb)

                Persistence & memory
                  --aof-file PATH          enable append-only-file persistence
                  --aof-fsync MODE         always | everysec (default) | no
                  --maxmemory SIZE         memory limit, e.g. 512mb (default 0 = unlimited)
                  --maxmemory-policy P     noeviction (default) | allkeys-random | volatile-ttl

                Operations
                  --config PATH            read options from a file (CLI overrides the file)
                  --metrics-port N         serve Prometheus metrics on /metrics (default off)
                  --slowlog-threshold-us N log commands slower than N microseconds (default 10000)

                Examples:
                  java -jar redCake.jar --port 6380
                  java -jar redCake.jar --port 6381 --replicaof 127.0.0.1 6379
                  java -jar redCake.jar --aof-file data/redcake.aof --maxmemory 256mb --maxmemory-policy allkeys-random
                """);
    }

    /** Thrown after {@code --help}/{@code --hash-password} so the application stops without a stack trace. */
    public static class SystemExitException extends RuntimeException {
    }
}
