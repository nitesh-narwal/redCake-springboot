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

    @Test
    void shouldParseOperationalOptions() {
        CommandLineConfig config = new CommandLineConfig(
                new DefaultApplicationArguments(
                        "--max-clients", "50",
                        "--timeout", "30",
                        "--allow-insecure",
                        "--aof-file", "data/x.aof",
                        "--aof-fsync", "always"
                )
        );

        assertEquals(50, config.getServerConfig().getMaxClients());
        assertEquals(30, config.getServerConfig().getIdleTimeoutSeconds());
        org.junit.jupiter.api.Assertions.assertTrue(config.getServerConfig().isAllowInsecure());
        org.junit.jupiter.api.Assertions.assertTrue(config.getPersistenceConfig().isEnabled());
        assertEquals(
                me.niteshh.redcake.config.PersistenceConfig.Fsync.ALWAYS,
                config.getPersistenceConfig().getFsync()
        );
    }

    @Test
    void persistenceIsOffByDefault() {
        CommandLineConfig config = new CommandLineConfig(new DefaultApplicationArguments());
        org.junit.jupiter.api.Assertions.assertFalse(config.getPersistenceConfig().isEnabled());
        assertEquals(10_000, config.getServerConfig().getMaxClients());
    }

    @Test
    void shouldRejectInvalidOperationalOptions() {
        for (String[] args : new String[][]{
                {"--max-clients", "0"},
                {"--max-clients", "abc"},
                {"--timeout", "-1"},
                {"--aof-fsync", "sometimes"},
                {"--aof-file"},
                {"--port"},
                {"--bogus"}
        }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new CommandLineConfig(new DefaultApplicationArguments(args)),
                    String.join(" ", args)
            );
        }
    }

    @Test
    void shouldReadApiKeyFromFile(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path file = dir.resolve("key");
        java.nio.file.Files.writeString(file, "  file-secret \n");

        CommandLineConfig config = new CommandLineConfig(
                new DefaultApplicationArguments("--api-key-file", file.toString())
        );
        assertEquals("file-secret", config.getApiKey());
    }

    @Test
    void networkExposureIsDetected() {
        org.junit.jupiter.api.Assertions.assertFalse(new RedCakeServerConfig(1, "127.0.0.1").isNetworkExposed());
        org.junit.jupiter.api.Assertions.assertTrue(new RedCakeServerConfig(1, "0.0.0.0").isNetworkExposed());
    }

    @Test
    void shouldParseMemoryTlsAclAndObservabilityOptions(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Path pw = dir.resolve("pw");
        java.nio.file.Files.writeString(pw, "storepass\n");

        CommandLineConfig config = new CommandLineConfig(new DefaultApplicationArguments(
                "--maxmemory", "256mb", "--maxmemory-policy", "volatile-ttl",
                "--tls-keystore", "k.p12", "--tls-keystore-password-file", pw.toString(),
                "--tls-truststore", "t.p12", "--tls-client-auth", "--tls-replication",
                "--acl-file", "users.acl", "--metrics-port", "9100",
                "--slowlog-threshold-us", "500", "--repl-backlog-size", "2mb"));

        assertEquals(256L * 1024 * 1024, config.getMemoryConfig().getMaxBytes());
        assertEquals(me.niteshh.redcake.config.MemoryConfig.Policy.VOLATILE_TTL, config.getMemoryConfig().getPolicy());
        org.junit.jupiter.api.Assertions.assertTrue(config.getTlsConfig().isServerTlsEnabled());
        org.junit.jupiter.api.Assertions.assertTrue(config.getTlsConfig().isClientAuth());
        org.junit.jupiter.api.Assertions.assertTrue(config.getTlsConfig().isReplicationTls());
        assertEquals("storepass", new String(config.getTlsConfig().getKeystorePassword()));
        assertEquals(java.nio.file.Path.of("users.acl"), config.getAclConfig().getFile());
        assertEquals(9100, config.getMetricsPort());
        assertEquals(500, config.getSlowlogThresholdMicros());
        assertEquals(2 * 1024 * 1024, config.getReplicationConfig().getBacklogBytes());
    }

    @Test
    void shouldRejectInvalidNewOptions() {
        for (String[] args : new String[][]{
                {"--maxmemory", "lots"},
                {"--maxmemory-policy", "lru"},
                {"--metrics-port", "0"},
                {"--tls-client-auth"},                       // needs a truststore
                {"--repl-backlog-size", "10"},               // below the 1 KiB minimum
                {"--slowlog-threshold-us", "fast"}
        }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new CommandLineConfig(new DefaultApplicationArguments(args)),
                    String.join(" ", args));
        }
    }

    @Test
    void configFileProvidesOptionsAndTheCommandLineWins(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Path file = dir.resolve("redcake.conf");
        java.nio.file.Files.writeString(file, """
                # sample configuration
                port 7000
                bind 127.0.0.1
                max-clients 77
                allow-insecure yes
                tls-replication no
                replicaof 10.0.0.5 6379
                maxmemory 1gb
                """);

        CommandLineConfig fromFile = new CommandLineConfig(
                new DefaultApplicationArguments("--config", file.toString()));
        assertEquals(7000, fromFile.getServerConfig().getPort());
        assertEquals(77, fromFile.getServerConfig().getMaxClients());
        org.junit.jupiter.api.Assertions.assertTrue(fromFile.getServerConfig().isAllowInsecure());
        org.junit.jupiter.api.Assertions.assertFalse(fromFile.getTlsConfig().isReplicationTls());
        assertEquals("10.0.0.5", fromFile.getReplicationConfig().getPrimaryHost());
        assertEquals(1024L * 1024 * 1024, fromFile.getMemoryConfig().getMaxBytes());

        // CLI overrides the file, even when the option appears BEFORE --config
        CommandLineConfig overridden = new CommandLineConfig(new DefaultApplicationArguments(
                "--port", "7100", "--config", file.toString(), "--replicaof", "10.9.9.9", "6400"));
        assertEquals(7100, overridden.getServerConfig().getPort());
        assertEquals("10.9.9.9", overridden.getReplicationConfig().getPrimaryHost());
        assertEquals(6400, overridden.getReplicationConfig().getPrimaryPort());
        assertEquals(77, overridden.getServerConfig().getMaxClients(), "unrelated file options still apply");
    }

    @Test
    void configFileProblemsAreReportedWithTheLineNumber(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        for (String bad : new String[]{"bogus 1", "port", "allow-insecure maybe", "replicaof onlyhost"}) {
            java.nio.file.Path file = dir.resolve("bad.conf");
            java.nio.file.Files.writeString(file, "# first\n" + bad + "\n");
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new CommandLineConfig(new DefaultApplicationArguments("--config", file.toString())), bad);
            org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("bad.conf:2"), e.getMessage());
        }
        assertThrows(IllegalArgumentException.class,
                () -> new CommandLineConfig(new DefaultApplicationArguments("--config", dir.resolve("missing").toString())));
    }
}
