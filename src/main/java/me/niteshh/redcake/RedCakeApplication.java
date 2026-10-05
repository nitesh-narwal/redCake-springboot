package me.niteshh.redcake;

import me.niteshh.redcake.cli.CommandLineConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point. Spring Boot is used only for dependency injection and
 * lifecycle ({@code @PostConstruct}/{@code @PreDestroy}); there is no web
 * server (see {@code spring.main.web-application-type=none}).
 *
 * <p>Command-line mistakes are reported as one readable line and exit code 2
 * instead of a Spring stack trace; {@code --help} exits with code 0.
 */
@SpringBootApplication
public class RedCakeApplication {

    public static void main(String[] args) {
        try {
            SpringApplication.run(RedCakeApplication.class, args);
        } catch (Exception e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof CommandLineConfig.SystemExitException) {
                    System.exit(0);
                }
                if (cause instanceof IllegalArgumentException
                        || cause instanceof IllegalStateException) {
                    // Configuration/startup problems carry an actionable message.
                    System.err.println("RedCake failed to start: " + cause.getMessage());
                    System.exit(2);
                }
            }
            throw e;
        }
    }
}
