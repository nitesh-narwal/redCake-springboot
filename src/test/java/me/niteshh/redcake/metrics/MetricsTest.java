package me.niteshh.redcake.metrics;

import me.niteshh.redcake.cli.CommandLineConfig;
import me.niteshh.redcake.config.RedCakeServerConfig;
import me.niteshh.redcake.persistence.AppendOnlyLog;
import me.niteshh.redcake.stats.CommandStats;
import me.niteshh.redcake.support.RespClient;
import me.niteshh.redcake.support.TestServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

class MetricsTest {

    private MetricsRenderer renderer(TestServer s) {
        return new MetricsRenderer(s.serverStats, s.commandStats, s.stack.store(), s.replicationStats,
                s.replicaManager, s.aof(), s.memoryConfig, s.slowLog);
    }

    @Test
    void rendersPrometheusTextWithCountersGaugesAndAHistogram() throws Exception {
        try (TestServer server = new TestServer().start(); RespClient c = server.client()) {
            c.call("SET", "a", "1");
            c.call("GET", "a");
            c.call("GET"); // error
            String text = renderer(server).render();

            assertTrue(text.contains("# TYPE redcake_keys gauge\nredcake_keys 1\n"), text);
            assertTrue(text.contains("redcake_commands_processed_total 3\n"), text);
            assertTrue(text.contains("redcake_connected_clients 1\n"), text);
            assertTrue(text.contains("redcake_command_calls_total{command=\"get\"} 2\n"), text);
            assertTrue(text.contains("redcake_command_failed_total{command=\"get\"} 1\n"), text);
            assertTrue(text.contains("redcake_aof_healthy 1\n"));
            assertTrue(text.contains("redcake_memory_used_bytes "));
            assertTrue(text.contains("# TYPE redcake_command_duration_seconds histogram"));
            assertTrue(text.contains("redcake_command_duration_seconds_bucket{le=\"+Inf\"} 3\n"), text);
            assertTrue(text.contains("redcake_command_duration_seconds_count 3\n"), text);

            // histogram buckets must be cumulative (never decreasing)
            long previous = -1;
            for (String line : text.split("\n")) {
                if (line.startsWith("redcake_command_duration_seconds_bucket")) {
                    long value = Long.parseLong(line.substring(line.lastIndexOf(' ') + 1));
                    assertTrue(value >= previous, "bucket went down: " + line);
                    previous = value;
                }
            }
        }
    }

    @Test
    void commandStatsPutDurationsIntoTheRightBucket() {
        CommandStats stats = new CommandStats();
        stats.record("GET", 50, false);       // <= 100us  -> bucket 0
        stats.record("GET", 700, false);      // <= 1ms    -> bucket 2
        stats.record("SET", 1_000_000, true); // > 100ms   -> overflow bucket
        long[] buckets = stats.bucketCounts();
        assertEquals(1, buckets[0]);
        assertEquals(1, buckets[2]);
        assertEquals(1, buckets[buckets.length - 1]);
        assertEquals(1_000_750, stats.totalMicros());
        assertEquals(2, stats.snapshot().get("GET").calls());
        assertEquals(1, stats.snapshot().get("SET").failed());
    }

    @Test
    void httpEndpointsServeMetricsAndHealth() throws Exception {
        int metricsPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            metricsPort = socket.getLocalPort();
        }
        try (TestServer server = new TestServer().start()) {
            MetricsServer http = new MetricsServer(
                    new CommandLineConfig(new DefaultApplicationArguments("--metrics-port", Integer.toString(metricsPort))),
                    new RedCakeServerConfig(server.port, "127.0.0.1"),
                    renderer(server), server.aof());
            http.start();
            try {
                HttpClient client = HttpClient.newHttpClient();
                HttpResponse<String> metrics = client.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + metricsPort + "/metrics")).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, metrics.statusCode());
                assertTrue(metrics.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));
                assertTrue(metrics.body().contains("redcake_uptime_seconds"));

                HttpResponse<String> health = client.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + metricsPort + "/health")).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, health.statusCode());
                assertEquals("ok\n", health.body());
            } finally {
                http.stop();
            }
        }
    }

    @Test
    void metricsServerStaysOffWithoutAPort() throws Exception {
        try (TestServer server = new TestServer().start()) {
            MetricsServer http = new MetricsServer(
                    new CommandLineConfig(new DefaultApplicationArguments()),
                    new RedCakeServerConfig(server.port, "127.0.0.1"),
                    renderer(server), AppendOnlyLog.disabled());
            assertDoesNotThrow(http::start);
            http.stop();
        }
    }
}
