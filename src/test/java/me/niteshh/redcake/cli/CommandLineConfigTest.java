package me.niteshh.redcake.cli;

import me.niteshh.redcake.config.RedCakeServerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommandLineConfigTest {

    @Test
    void shouldParsePortBindAddressAndReplicaTarget() {
        CommandLineConfig config = new CommandLineConfig(
                new DefaultApplicationArguments(
                        "--bind", "127.0.0.1",
                        "--port", "6381",
                        "--replicaof", "127.0.0.1", "6379"
                )
        );

        assertEquals(6381, config.getServerConfig().getPort());
        assertEquals(
                "127.0.0.1",
                config.getServerConfig().getBindAddress()
        );
        assertEquals(
                "127.0.0.1",
                config.getReplicationConfig().getPrimaryHost()
        );
        assertEquals(
                6379,
                config.getReplicationConfig().getPrimaryPort()
        );
    }

    @Test
    void shouldRejectDuplicatePort() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CommandLineConfig(
                        new DefaultApplicationArguments(
                                "--port", "6381",
                                "--port", "6382"
                        )
                )
        );
    }

    @Test
    void shouldUseLoopbackByDefault() {
        CommandLineConfig config = new CommandLineConfig(
                new DefaultApplicationArguments()
        );

        assertEquals(
                RedCakeServerConfig.DEFAULT_BIND_ADDRESS,
                config.getServerConfig().getBindAddress()
        );
    }

    @Test
    void shouldParseApiKey() {
        CommandLineConfig config = new CommandLineConfig(
                new DefaultApplicationArguments(
                        "--api-key", "test-secret"
                )
        );

        assertEquals("test-secret", config.getApiKey());
    }

    @Test
    void shouldRejectBlankApiKey() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CommandLineConfig(
                        new DefaultApplicationArguments(
                                "--api-key", " "
                        )
                )
        );
    }
}
