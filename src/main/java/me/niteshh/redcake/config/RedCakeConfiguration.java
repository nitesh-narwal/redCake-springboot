package me.niteshh.redcake.config;

import me.niteshh.redcake.cli.CommandLineConfig;
import me.niteshh.redcake.replication.ReplicationConfig;
import me.niteshh.redcake.stats.SlowLog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the immutable (or deliberately mutable, for CONFIG SET) configuration
 * objects parsed by {@link CommandLineConfig} as Spring beans. Command-line
 * arguments are the single source of truth, so every component sees one
 * consistent instance.
 */
@Configuration
public class RedCakeConfiguration {

    @Bean
    public RedCakeServerConfig serverConfig(CommandLineConfig commandLineConfig) {
        return commandLineConfig.getServerConfig();
    }

    @Bean
    public ReplicationConfig replicationConfig(CommandLineConfig commandLineConfig) {
        return commandLineConfig.getReplicationConfig();
    }

    @Bean
    public RedCakeAuthConfig authConfig(CommandLineConfig commandLineConfig) {
        return new RedCakeAuthConfig(commandLineConfig.getApiKey());
    }

    @Bean
    public PersistenceConfig persistenceConfig(CommandLineConfig commandLineConfig) {
        return commandLineConfig.getPersistenceConfig();
    }

    @Bean
    public MemoryConfig memoryConfig(CommandLineConfig commandLineConfig) {
        return commandLineConfig.getMemoryConfig();
    }

    @Bean
    public TlsConfig tlsConfig(CommandLineConfig commandLineConfig) {
        return commandLineConfig.getTlsConfig();
    }

    @Bean
    public AclConfig aclConfig(CommandLineConfig commandLineConfig) {
        return commandLineConfig.getAclConfig();
    }

    /** The slow log starts with the threshold given by {@code --slowlog-threshold-us}. */
    @Bean
    public SlowLog slowLog(CommandLineConfig commandLineConfig) {
        SlowLog slowLog = new SlowLog();
        slowLog.setThresholdMicros(commandLineConfig.getSlowlogThresholdMicros());
        return slowLog;
    }
}
