package me.niteshh.redcake.cli;

import me.niteshh.redcake.config.RedCakeServerConfig;
import me.niteshh.redcake.replication.ReplicationConfig;
import me.niteshh.redcake.replication.ReplicationRole;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

@Component
public final class CommandLineConfig {
    private static final int DEFAULT_PORT = 6379;

    private final RedCakeServerConfig serverConfig;

    private final ReplicationConfig replicationConfig;
    private final String apiKey;

    public CommandLineConfig(
            ApplicationArguments arguments
    ) {

        ParsedArguments parsed =
                parse(
                        arguments.getSourceArgs()
                );

        this.serverConfig =
                new RedCakeServerConfig(
                        parsed.port(),
                        parsed.bindAddress()
                );

        this.replicationConfig =
                new ReplicationConfig(
                        parsed.role(),
                        parsed.primaryHost(),
                        parsed.primaryPort()
                );
        this.apiKey = parsed.apiKey();
    }

    public RedCakeServerConfig getServerConfig() {
        return serverConfig;
    }

    public ReplicationConfig getReplicationConfig() {
        return replicationConfig;
    }

    public String getApiKey() {
        return apiKey;
    }

    private ParsedArguments parse(
            String[] args
    ) {

        int port = DEFAULT_PORT;
        String bindAddress =
                RedCakeServerConfig.DEFAULT_BIND_ADDRESS;
        String apiKey = System.getenv("REDCAKE_API_KEY");

        ReplicationRole role =
                ReplicationRole.PRIMARY;

        String primaryHost = null;

        int primaryPort = DEFAULT_PORT;
        boolean portSpecified = false;
        boolean replicaOfSpecified = false;

        for (int i = 0; i < args.length; i++) {

            String argument = args[i];

            switch (argument) {

                case "--port" -> {
                    if (portSpecified) {
                        throw new IllegalArgumentException(
                                "--port may only be specified once"
                        );
                    }

                    if (i + 1 >= args.length) {

                        throw new IllegalArgumentException(
                                "--port requires a value"
                        );
                    }

                    portSpecified = true;
                    port =
                            parsePort(
                                    args[++i],
                                    "--port"
                            );
                }

                case "--bind" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException(
                                "--bind requires an address"
                        );
                    }

                    bindAddress = args[++i];
                    if (bindAddress.isBlank()) {
                        throw new IllegalArgumentException(
                                "--bind requires a non-empty address"
                        );
                    }
                }

                case "--api-key" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException(
                                "--api-key requires a value"
                        );
                    }

                    apiKey = args[++i];
                    if (apiKey.isBlank()) {
                        throw new IllegalArgumentException(
                                "--api-key must not be empty"
                        );
                    }
                }

                case "--replicaof" -> {
                    if (replicaOfSpecified) {
                        throw new IllegalArgumentException(
                                "--replicaof may only be specified once"
                        );
                    }

                    if (i + 2 >= args.length) {

                        throw new IllegalArgumentException(
                                "--replicaof requires "
                                        + "<host> <port>"
                        );
                    }

                    replicaOfSpecified = true;
                    primaryHost =
                            args[++i];

                    if (primaryHost.isBlank()) {
                        throw new IllegalArgumentException(
                                "Primary host must not be empty"
                        );
                    }

                    primaryPort =
                            parsePort(
                                    args[++i],
                                    "--replicaof"
                            );

                    role =
                            ReplicationRole.REPLICA;
                }

                case "--help", "-h" -> {

                    printUsage();

                    throw new SystemExitException();
                }

                default -> {

                    throw new IllegalArgumentException(
                            "Unknown argument: "
                                    + argument
                    );
                }
            }
        }

        return new ParsedArguments(
                port,
                bindAddress,
                role,
                primaryHost,
                primaryPort,
                apiKey
        );
    }

    private int parsePort(
            String value,
            String option
    ) {

        final int port;

        try {

            port =
                    Integer.parseInt(value);

        } catch (NumberFormatException e) {

            throw new IllegalArgumentException(
                    option
                            + " requires a valid port: "
                            + value
            );
        }

        if (port < 1 || port > 65535) {

            throw new IllegalArgumentException(
                    "Port must be between 1 and 65535"
            );
        }

        return port;
    }

    private void printUsage() {

        System.out.println();
        System.out.println(
                "RedCake usage:"
        );
        System.out.println();
        System.out.println(
                "  java -jar redCake.jar"
        );
        System.out.println(
                "      Start primary on 127.0.0.1:6379"
        );
        System.out.println();
        System.out.println(
                "  java -jar redCake.jar --port 6380"
        );
        System.out.println(
                "      Start primary on 127.0.0.1:6380"
        );
        System.out.println();
        System.out.println(
                "  java -jar redCake.jar --bind 0.0.0.0 --port 6379"
        );
        System.out.println(
                "      Expose the server to network interfaces"
        );
        System.out.println();
        System.out.println(
                "  java -jar redCake.jar "
                        + "--port 6381 "
                        + "--replicaof 127.0.0.1 6379"
        );
        System.out.println(
                "      Start replica on 6381"
        );
        System.out.println();
    }

    private record ParsedArguments(
            int port,
            String bindAddress,
            ReplicationRole role,
            String primaryHost,
            int primaryPort,
            String apiKey
    ) {
    }

    public static class SystemExitException
            extends RuntimeException {
    }
}
