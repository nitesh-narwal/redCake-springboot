package me.niteshh.redcake.config;

import lombok.Getter;
@Getter
public class RedCakeServerConfig {

    public static final int DEFAULT_PORT = 6379;
    public static final String DEFAULT_BIND_ADDRESS = "127.0.0.1";

    /**
     * Port on which the RedCake TCP server listens.
     * <p>
     * Default Redis-compatible port.
     */
    private final int port;
    private final String bindAddress;

    public RedCakeServerConfig(int port) {
        this(port, DEFAULT_BIND_ADDRESS);
    }

    public RedCakeServerConfig(int port, String bindAddress) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(
                    "Port must be between 1 and 65535: " + port
            );
        }

        if (bindAddress == null || bindAddress.isBlank()) {
            throw new IllegalArgumentException(
                    "Bind address must not be empty"
            );
        }

        this.port = port;
        this.bindAddress = bindAddress;
    }
}