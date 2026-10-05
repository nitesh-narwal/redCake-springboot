package me.niteshh.redcake.metrics;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import me.niteshh.redcake.cli.CommandLineConfig;
import me.niteshh.redcake.config.RedCakeServerConfig;
import me.niteshh.redcake.persistence.AppendOnlyLog;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * Tiny HTTP endpoint for monitoring, off unless {@code --metrics-port N} is given.
 *
 * <ul>
 *   <li>{@code GET /metrics} - Prometheus text format ({@link MetricsRenderer}).</li>
 *   <li>{@code GET /health} - {@code 200 ok}, or {@code 503} when the AOF
 *       can no longer be written (the node refuses writes then); suitable as a
 *       Kubernetes/Docker health probe.</li>
 * </ul>
 * It uses the JDK's built-in {@code com.sun.net.httpserver}, so there is no
 * extra dependency, and it listens on the same address as the database
 * (loopback by default). The endpoint is unauthenticated and exposes only
 * counters and sizes - never keys or values.
 */
@Slf4j
@Component
public class MetricsServer {

    private final int port;
    private final String bindAddress;
    private final MetricsRenderer renderer;
    private final AppendOnlyLog appendOnlyLog;

    private HttpServer server;

    public MetricsServer(
            CommandLineConfig commandLineConfig,
            RedCakeServerConfig serverConfig,
            MetricsRenderer renderer,
            AppendOnlyLog appendOnlyLog
    ) {
        this.port = commandLineConfig.getMetricsPort();
        this.bindAddress = serverConfig.getBindAddress();
        this.renderer = renderer;
        this.appendOnlyLog = appendOnlyLog;
    }

    @PostConstruct
    public void start() throws IOException {
        if (port == 0) {
            return;
        }
        server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        server.createContext("/metrics", exchange ->
                respond(exchange, 200, "text/plain; version=0.0.4; charset=utf-8", renderer.render()));
        server.createContext("/health", exchange -> {
            boolean healthy = appendOnlyLog.isHealthy();
            respond(exchange, healthy ? 200 : 503, "text/plain", healthy ? "ok\n" : "aof write failure\n");
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        log.info("Metrics available on http://{}:{}/metrics", bindAddress, port);
    }

    private void respond(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @PreDestroy
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }
}
