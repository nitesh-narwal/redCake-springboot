package me.niteshh.redcake.replication;

import me.niteshh.redcake.command.CommandHandler;
import me.niteshh.redcake.config.RedCakeAuthConfig;
import me.niteshh.redcake.replication.primary.ReplicaConnection;
import me.niteshh.redcake.replication.primary.ReplicaManager;
import me.niteshh.redcake.replication.replica.ReplicaSyncManager;
import me.niteshh.redcake.resp.RespParser;
import me.niteshh.redcake.resp.RespWriter;
import me.niteshh.redcake.server.ClientHandler;
import me.niteshh.redcake.support.RespClient;
import me.niteshh.redcake.support.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * {@code min-replicas-to-write}: bounds how much a primary can lose if it fails right after acknowledging a
 * write - it refuses to acknowledge anything while too few replicas are keeping up.
 */
class MinReplicasToWriteTest {

    private TestSupport.Stack stack;
    private ServerSocket listener;
    private ReplicaManager replicaManager;
    private ReplicationConfig config;
    private ClientHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        stack = TestSupport.newStack();
        listener = new ServerSocket(0);
        replicaManager = new ReplicaManager();
        config = new ReplicationConfig(ReplicationRole.PRIMARY, null, 6379);
        ReplicationManager replication = new ReplicationManager(
                config, replicaManager, mock(ReplicaSyncManager.class), stack.store());
        CommandHandler commands = TestSupport.allCommands(stack.store(), ReplicationRole.PRIMARY);
        handler = new ClientHandler(new RespParser(), commands, new RespWriter(), replication,
                new RedCakeAuthConfig(null));
    }

    @AfterEach
    void tearDown() throws Exception {
        listener.close();
        stack.stop();
    }

    private RespClient connect() throws Exception {
        Thread.ofVirtual().start(() -> {
            try {
                handler.handleClient(listener.accept());
            } catch (Exception ignored) {
                // the test ends the connection
            }
        });
        return new RespClient("127.0.0.1", listener.getLocalPort());
    }

    /** A replica link without a socket that has acknowledged offset 0 just now. */
    private ReplicaConnection attachedReplica(String id) {
        ReplicaConnection replica = new ReplicaConnection(id, new ByteArrayOutputStream());
        replica.recordAck(0);
        replicaManager.register(replica);
        return replica;
    }

    @Test
    void writesAreUnrestrictedByDefault() throws Exception {
        try (RespClient client = connect()) {
            assertEquals("+OK", client.call("SET", "k", "v"));
        }
    }

    @Test
    void aPrimaryWithTooFewGoodReplicasRefusesWritesButStillServesReads() throws Exception {
        config.setMinReplicasToWrite(1);
        stack.store().set("existing", "yes");

        try (RespClient client = connect()) {
            assertEquals("-NOREPLICAS Not enough good replicas to write.", client.call("SET", "k", "v"));
            assertNull(stack.store().get("k"), "a refused write must not be applied");
            assertEquals("\"yes\"", client.call("GET", "existing"));

            attachedReplica("r1");
            assertEquals("+OK", client.call("SET", "k", "v"));
        }
    }

    @Test
    void aReplicaThatNeverAcknowledgedDoesNotCount() throws Exception {
        config.setMinReplicasToWrite(1);
        replicaManager.register(new ReplicaConnection("silent", new ByteArrayOutputStream())); // ack offset -1

        try (RespClient client = connect()) {
            assertTrue(client.call("SET", "k", "v").startsWith("-NOREPLICAS"));
        }
    }

    @Test
    void aReplicaThatStoppedAcknowledgingStopsCountingAfterTheMaxLag() throws Exception {
        config.setMinReplicasToWrite(1);
        config.setMinReplicasMaxLagSeconds(1);
        attachedReplica("r1");

        try (RespClient client = connect()) {
            assertEquals("+OK", client.call("SET", "k", "1"));
            Thread.sleep(2_100); // no acknowledgement for > 1 s
            assertTrue(client.call("SET", "k", "2").startsWith("-NOREPLICAS"));
            assertEquals("1", stack.store().get("k"));
        }
    }

    @Test
    void theRequiredNumberIsCountedNotJustPresence() throws Exception {
        config.setMinReplicasToWrite(2);
        attachedReplica("r1");

        try (RespClient client = connect()) {
            assertTrue(client.call("SET", "k", "v").startsWith("-NOREPLICAS"));
            attachedReplica("r2");
            assertEquals("+OK", client.call("SET", "k", "v"));
        }
    }

    @Test
    void invalidValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> config.setMinReplicasToWrite(-1));
        assertThrows(IllegalArgumentException.class, () -> config.setMinReplicasMaxLagSeconds(0));
    }
}
