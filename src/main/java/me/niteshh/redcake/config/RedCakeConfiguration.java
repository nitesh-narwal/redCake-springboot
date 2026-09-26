package me.niteshh.redcake.config;

import me.niteshh.redcake.cli.CommandLineConfig;
import me.niteshh.redcake.replication.ReplicationConfig;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
    public RedCakeAuthConfig authConfig(
            CommandLineConfig commandLineConfig
    ) {
        return new RedCakeAuthConfig(
                commandLineConfig.getApiKey()
        );
    }

}
