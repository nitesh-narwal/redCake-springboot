package me.niteshh.redcake.config;

import lombok.Getter;

/**
 * Network-level settings of this node: where to listen and how many / how
 * patient clients may be. Immutable and validated on construction so the rest
 * of the code can trust the values.
 */
@Getter
public class RedCakeServerConfig {

    public static final int DEFAULT_PORT = 6379;
    public static final String DEFAULT_BIND_ADDRESS = "127.0.0.1";
    public static final int DEFAULT_MAX_CLIENTS = 10_000;

    /** Port on which the RedCake TCP server listens (default: Redis-compatible 6379). */
    private final int port;
    private final String bindAddress;
    /** Hard cap on simultaneously served client connections. */
    private final int maxClients;
    /** Close connections idle for this many seconds; 0 disables the timeout. */
    private final int idleTimeoutSeconds;
    /** Allow listening on a non-loopback address without an API key. */
    private final boolean allowInsecure;

    public RedCakeServerConfig(int port) {
        this(port, DEFAULT_BIND_ADDRESS);
    }

    public RedCakeServerConfig(int port, String bindAddress) {
        this(port, bindAddress, DEFAULT_MAX_CLIENTS, 0, false);
    }

    public RedCakeServerConfig(
            int port,
            String bindAddress,
            int maxClients,
            int idleTimeoutSeconds,
            boolean allowInsecure
    ) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(
                    "Port must be between 1 and 65535: " + port
            );
        }
        if (bindAddress == null || bindAddress.isBlank()) {
            throw new IllegalArgumentException("Bind address must not be empty");
        }
        if (maxClients < 1) {
            throw new IllegalArgumentException("max-clients must be at least 1");
        }
        if (idleTimeoutSeconds < 0) {
            throw new IllegalArgumentException("timeout must not be negative");
        }

        this.port = port;
        this.bindAddress = bindAddress;
        this.maxClients = maxClients;
        this.idleTimeoutSeconds = idleTimeoutSeconds;
        this.allowInsecure = allowInsecure;
    }

    /**
     * @return {@code true} if the bind address is wildcard or non-loopback,
     * i.e. reachable from other machines
     */
    public boolean isNetworkExposed() {
        try {
            java.net.InetAddress address = java.net.InetAddress.getByName(bindAddress);
            return !address.isLoopbackAddress();
        } catch (java.net.UnknownHostException e) {
            return true; // unknown host: assume the worst
        }
    }
}
