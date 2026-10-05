package me.niteshh.redcake.server;

import me.niteshh.redcake.command.CommandHandler;
import me.niteshh.redcake.config.RedCakeAuthConfig;
import me.niteshh.redcake.replication.ReplicationConfig;
import me.niteshh.redcake.replication.ReplicationManager;
import me.niteshh.redcake.replication.ReplicationRole;
import me.niteshh.redcake.replication.primary.ReplicaManager;
import me.niteshh.redcake.replication.replica.PrimaryConnection;
import me.niteshh.redcake.replication.replica.ReplicaSyncManager;
import me.niteshh.redcake.resp.RespParser;
import me.niteshh.redcake.resp.RespWriter;
import me.niteshh.redcake.support.RespClient;
import me.niteshh.redcake.support.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Connection-level behaviour: auth rules, pipelining, inline commands, QUIT, protocol errors. */
class ClientHandlerBehaviourTest {

    private TestSupport.Stack stack;
    private ServerSocket listener;

    @BeforeEach
    void setUp() throws Exception {
        stack = TestSupport.newStack();
        listener = new ServerSocket(0);
    }

    @AfterEach
    void tearDown() throws Exception {
        listener.close();
        stack.stop();
    }

    private ClientHandler handler(RedCakeAuthConfig auth) {
        CommandHandler commands = TestSupport.allCommands(stack.store(), ReplicationRole.PRIMARY);
        ReplicationManager replication = new ReplicationManager(
                new ReplicationConfig(ReplicationRole.PRIMARY, null, 6379),
                new ReplicaManager(),
                mock(ReplicaSyncManager.class),
                stack.store());
        return new ClientHandler(new RespParser(), commands, new RespWriter(), replication, auth);
    }

    private RespClient connect(ClientHandler handler) throws Exception {
        Thread.ofVirtual().start(() -> {
            try {
                handler.handleClient(listener.accept());
            } catch (Exception ignored) {
                // test ends the connection
            }
        });
        return new RespClient("127.0.0.1", listener.getLocalPort());
    }

    /** Regression B5: AUTH on a server without a password must not lock the client out. */
    @Test
    void authWithoutConfiguredPasswordIsAnErrorAndDoesNotLockOut() throws Exception {
        try (RespClient client = connect(handler(new RedCakeAuthConfig(null)))) {
            assertTrue(client.call("AUTH", "whatever").startsWith("-ERR AUTH"));
            assertEquals("+PONG", client.call("PING"));
        }
    }

    /** Regression B5: a wrong AUTH after a good one must not log the session out. */
    @Test
    void failedAuthDoesNotDropAnAuthenticatedSession() throws Exception {
        try (RespClient client = connect(handler(new RedCakeAuthConfig("secret")))) {
            assertEquals("-NOAUTH authentication required", client.call("GET", "x"));
            assertEquals("+OK", client.call("AUTH", "secret"));
            assertTrue(client.call("AUTH", "wrong").startsWith("-WRONGPASS"));
            assertEquals("+PONG", client.call("PING"));
        }
    }

    @Test
    void authAcceptsDefaultUserForm() throws Exception {
        try (RespClient client = connect(handler(new RedCakeAuthConfig("secret")))) {
            assertEquals("+OK", client.call("AUTH", "default", "secret"));
            assertTrue(client.call("AUTH", "root", "secret").startsWith("-WRONGPASS"));
        }
    }

    @Test
    void pipelinedCommandsAreAllAnsweredInOrder() throws Exception {
        try (RespClient client = connect(handler(new RedCakeAuthConfig(null)))) {
            StringBuilder burst = new StringBuilder();
            for (int i = 0; i < 200; i++) {
                burst.append("*3\r\n$3\r\nSET\r\n$2\r\nk")
                        .append(i % 10).append("\r\n$1\r\nv\r\n");
                burst.append("*2\r\n$4\r\nINCR\r\n$3\r\ncnt\r\n");
            }
            client.sendRaw(burst.toString());
            for (int i = 0; i < 200; i++) {
                assertEquals("+OK", client.read());
                assertEquals(":" + (i + 1), client.read());
            }
        }
    }

    @Test
    void inlineCommandsWorkLikeTelnet() throws Exception {
        try (RespClient client = connect(handler(new RedCakeAuthConfig(null)))) {
            client.sendRaw("PING\r\n");
            assertEquals("+PONG", client.read());
            client.sendRaw("SET greeting hello\r\nGET greeting\r\n");
            assertEquals("+OK", client.read());
            assertEquals("\"hello\"", client.read());
        }
    }

    @Test
    void protocolViolationGetsAnErrorReplyThenClose() throws Exception {
        try (RespClient client = connect(handler(new RedCakeAuthConfig(null)))) {
            client.sendRaw("*1\r\n$abc\r\n");
            assertTrue(client.read().startsWith("-ERR Protocol error"));
            assertNull(client.read(), "connection must be closed after a protocol error");
        }
    }

    @Test
    void quitRepliesOkAndCloses() throws Exception {
        try (RespClient client = connect(handler(new RedCakeAuthConfig(null)))) {
            assertEquals("+OK", client.call("QUIT"));
            assertNull(client.read());
        }
    }

    @Test
    void aCommandBugDoesNotKillTheConnection() throws Exception {
        try (RespClient client = connect(handler(new RedCakeAuthConfig(null)))) {
            assertTrue(client.call("NOSUCHCOMMAND").startsWith("-ERR unknown command"));
            assertEquals("+PONG", client.call("PING"));
        }
    }

    @Test
    void replicasRefuseWritesButServeReads() throws Exception {
        CommandHandler commands = TestSupport.allCommands(stack.store(), ReplicationRole.REPLICA);
        ReplicationManager replication = new ReplicationManager(
                new ReplicationConfig(ReplicationRole.REPLICA, "127.0.0.1", 6379),
                new ReplicaManager(),
                mock(ReplicaSyncManager.class), stack.store());
        ClientHandler handler = new ClientHandler(
                new RespParser(), commands, new RespWriter(), replication, new RedCakeAuthConfig(null));
        stack.store().set("k", "v");

        try (RespClient client = connect(handler)) {
            assertEquals("-READONLY replica does not accept writes", client.call("SET", "a", "b"));
            assertEquals("-READONLY replica does not accept writes", client.call("FLUSHALL"));
            assertEquals("\"v\"", client.call("GET", "k"));
        }
    }
}
