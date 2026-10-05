package me.niteshh.redcake.server;

import me.niteshh.redcake.command.CommandHandler;
import me.niteshh.redcake.config.RedCakeAuthConfig;
import me.niteshh.redcake.config.RedCakeServerConfig;
import me.niteshh.redcake.replication.ReplicationConfig;
import me.niteshh.redcake.replication.ReplicationManager;
import me.niteshh.redcake.replication.ReplicationRole;
import me.niteshh.redcake.replication.primary.ReplicaManager;
import me.niteshh.redcake.replication.replica.PrimaryConnection;
import me.niteshh.redcake.replication.replica.ReplicaSyncManager;
import me.niteshh.redcake.resp.RespParser;
import me.niteshh.redcake.resp.RespWriter;
import me.niteshh.redcake.stats.ServerStats;
import me.niteshh.redcake.support.RespClient;
import me.niteshh.redcake.support.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** The real listener: protected mode, client cap, idle timeout. */
class RedCakeServerTest {

    private TestSupport.Stack stack;
    private RedCakeServer server;

    @BeforeEach
    void setUp() {
        stack = TestSupport.newStack();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
        stack.stop();
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private RedCakeServer newServer(RedCakeServerConfig config, RedCakeAuthConfig auth) {
        CommandHandler commands = TestSupport.allCommands(stack.store(), ReplicationRole.PRIMARY);
        ServerStats stats = new ServerStats();
        ReplicationManager replication = new ReplicationManager(
                new ReplicationConfig(ReplicationRole.PRIMARY, null, 6379),
                new ReplicaManager(),
                mock(ReplicaSyncManager.class), stack.store());
        ClientHandler handler = new ClientHandler(
                new RespParser(), commands, new RespWriter(), replication, auth);
        return new RedCakeServer(handler, config, auth, stats);
    }

    @Test
    void protectedModeRefusesOpenNetworkListener() throws Exception {
        server = newServer(
                new RedCakeServerConfig(freePort(), "0.0.0.0", 10, 0, false),
                new RedCakeAuthConfig(null));
        IllegalStateException e = assertThrows(IllegalStateException.class, server::start);
        assertTrue(e.getMessage().contains("without authentication"), e.getMessage());
    }

    @Test
    void networkListenerIsAllowedWithApiKeyOrExplicitOptOut() throws Exception {
        server = newServer(
                new RedCakeServerConfig(freePort(), "0.0.0.0", 10, 0, false),
                new RedCakeAuthConfig("secret"));
        assertDoesNotThrow(server::start);
        server.stop();

        server = newServer(
                new RedCakeServerConfig(freePort(), "0.0.0.0", 10, 0, true),
                new RedCakeAuthConfig(null));
        assertDoesNotThrow(server::start);
    }

    @Test
    void clientsBeyondMaxClientsAreToldAndDropped() throws Exception {
        int port = freePort();
        server = newServer(new RedCakeServerConfig(port, "127.0.0.1", 2, 0, false),
                new RedCakeAuthConfig(null));
        server.start();

        try (RespClient a = new RespClient("127.0.0.1", port);
             RespClient b = new RespClient("127.0.0.1", port)) {
            assertEquals("+PONG", a.call("PING"));
            assertEquals("+PONG", b.call("PING"));

            try (RespClient c = new RespClient("127.0.0.1", port)) {
                assertEquals("-ERR max number of clients reached", c.read());
            }
        }
    }

    @Test
    void idleClientsAreDisconnected() throws Exception {
        int port = freePort();
        server = newServer(new RedCakeServerConfig(port, "127.0.0.1", 10, 1, false),
                new RedCakeAuthConfig(null));
        server.start();

        try (RespClient client = new RespClient("127.0.0.1", port)) {
            assertEquals("+PONG", client.call("PING"));
            long start = System.currentTimeMillis();
            assertNull(client.read(), "server should close an idle connection");
            assertTrue(System.currentTimeMillis() - start < 4_000);
        }
    }
}
