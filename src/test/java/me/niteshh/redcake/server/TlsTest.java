package me.niteshh.redcake.server;

import me.niteshh.redcake.command.CommandHandler;
import me.niteshh.redcake.config.RedCakeAuthConfig;
import me.niteshh.redcake.config.RedCakeServerConfig;
import me.niteshh.redcake.config.TlsConfig;
import me.niteshh.redcake.replication.ReplicationConfig;
import me.niteshh.redcake.replication.ReplicationRole;
import me.niteshh.redcake.replication.replica.PrimaryConnection;
import me.niteshh.redcake.replication.replica.ReplicaSyncManager;
import me.niteshh.redcake.security.TlsSupport;
import me.niteshh.redcake.stats.ReplicationStats;
import me.niteshh.redcake.support.RespClient;
import me.niteshh.redcake.support.TestServer;
import me.niteshh.redcake.support.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TLS for clients and for replication, including mutual TLS. A throw-away
 * self-signed certificate is created with the JDK's {@code keytool}.
 */
class TlsTest {

    private static final char[] PASSWORD = "changeit".toCharArray();

    @TempDir
    Path dir;

    private TestServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    /** Creates a PKCS12 keystore with a certificate valid for localhost and 127.0.0.1. */
    private Path newKeystore(String name) throws Exception {
        Path store = dir.resolve(name);
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process process = new ProcessBuilder(keytool, "-genkeypair",
                "-alias", "redcake", "-keyalg", "RSA", "-keysize", "2048",
                "-storetype", "PKCS12", "-keystore", store.toString(),
                "-storepass", new String(PASSWORD), "-validity", "2",
                "-dname", "CN=localhost", "-ext", "san=dns:localhost,ip:127.0.0.1")
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), "keytool failed: " + output);
        return store;
    }

    private TlsConfig tls(Path keystore, Path truststore, boolean clientAuth, boolean replication) {
        return new TlsConfig(keystore, PASSWORD, truststore, PASSWORD, clientAuth, replication);
    }

    private RespClient tlsClient(SSLContext context) throws Exception {
        Socket socket = context.getSocketFactory().createSocket("localhost", server.port);
        return new RespClient(socket);
    }

    @Test
    void clientsCanTalkOverTlsAndPlainTextIsRefused() throws Exception {
        Path store = newKeystore("server.p12");
        TlsConfig config = tls(store, store, false, false);
        server = new TestServer().withTls(config).start();

        try (RespClient c = tlsClient(TlsSupport.createContext(config))) {
            assertEquals("+PONG", c.call("PING"));
            assertEquals("+OK", c.call("SET", "secret", "value"));
            assertEquals("\"value\"", c.call("GET", "secret"));
        }

        // A plain-text client must not be served (the server expects a TLS handshake).
        try (RespClient plain = server.client()) {
            plain.setTimeout(3_000);
            String reply;
            try {
                reply = plain.call("PING");
            } catch (IOException closed) {
                reply = null;
            }
            assertNotEquals("+PONG", reply);
        }
    }

    @Test
    void aClientThatDoesNotTrustTheCertificateFailsTheHandshake() throws Exception {
        Path serverStore = newKeystore("server.p12");
        Path otherStore = newKeystore("other.p12"); // a different self-signed identity
        server = new TestServer().withTls(tls(serverStore, serverStore, false, false)).start();

        SSLContext distrustful = TlsSupport.createContext(tls(null, otherStore, false, false));
        assertThrows(SSLException.class, () -> {
            try (RespClient c = tlsClient(distrustful)) {
                c.call("PING");
            }
        });
    }

    @Test
    void mutualTlsRequiresAClientCertificate() throws Exception {
        Path serverStore = newKeystore("server.p12");
        Path clientStore = newKeystore("client.p12");
        // The server trusts the CLIENT certificate; the client trusts the SERVER certificate.
        TlsConfig serverConfig = tls(serverStore, clientStore, true, false);
        server = new TestServer().withTls(serverConfig).start();

        SSLContext withCertificate = TlsSupport.createContext(tls(clientStore, serverStore, false, false));
        try (RespClient c = tlsClient(withCertificate)) {
            assertEquals("+PONG", c.call("PING"));
        }

        SSLContext withoutCertificate = TlsSupport.createContext(tls(null, serverStore, false, false));
        assertThrows(IOException.class, () -> {
            try (RespClient c = tlsClient(withoutCertificate)) {
                c.call("PING");
            }
        }, "a client without a certificate must be rejected");
    }

    @Test
    void clientAuthWithoutATruststoreIsAConfigurationError() throws Exception {
        Path store = newKeystore("server.p12");
        assertThrows(IllegalArgumentException.class, () -> tls(store, null, true, false));
    }

    @Test
    void replicationCanRunOverTls() throws Exception {
        Path store = newKeystore("server.p12");
        TlsConfig primaryTls = tls(store, store, false, false);
        server = new TestServer().withTls(primaryTls).withApiKey("link-secret").start();
        server.stack.store().set("replicated", "over-tls");

        TestSupport.Stack replica = TestSupport.newStack();
        // The replica trusts the primary's certificate and verifies the host name "localhost".
        PrimaryConnection connection = new PrimaryConnection(tls(null, store, false, true));
        CommandHandler replicaCommands = TestSupport.allCommands(replica.store(), ReplicationRole.REPLICA);
        ReplicaSyncManager sync = new ReplicaSyncManager(
                connection,
                new ReplicationConfig(ReplicationRole.REPLICA, "localhost", server.port),
                replicaCommands, new RedCakeAuthConfig("link-secret"), replica.store(),
                new RedCakeServerConfig(1234, "127.0.0.1"), new ReplicationStats());
        try {
            sync.start();
            assertTrue(TestSupport.await(10_000, () -> "over-tls".equals(replica.store().get("replicated"))),
                    "the replica must sync through the TLS link");
        } finally {
            sync.stop();
            replica.stop();
        }
    }

    @Test
    void replicaRefusesAPrimaryWhoseCertificateItDoesNotTrust() throws Exception {
        Path primaryStore = newKeystore("primary.p12");
        Path strangerStore = newKeystore("stranger.p12");
        server = new TestServer().withTls(tls(primaryStore, primaryStore, false, false)).start();
        server.stack.store().set("k", "v");

        TestSupport.Stack replica = TestSupport.newStack();
        ReplicaSyncManager sync = new ReplicaSyncManager(
                new PrimaryConnection(tls(null, strangerStore, false, true)),
                new ReplicationConfig(ReplicationRole.REPLICA, "localhost", server.port),
                TestSupport.allCommands(replica.store(), ReplicationRole.REPLICA),
                new RedCakeAuthConfig(null), replica.store(),
                new RedCakeServerConfig(1234, "127.0.0.1"), new ReplicationStats());
        try {
            sync.start();
            Thread.sleep(2_000);
            assertNull(replica.store().get("k"), "an untrusted primary must never be synced from");
            assertEquals(List.of(), server.replicaManager.getReplicas());
        } finally {
            sync.stop();
            replica.stop();
        }
    }

    @Test
    void missingOrWrongKeystoreIsReportedClearly() {
        TlsConfig missing = tls(dir.resolve("nope.p12"), null, false, false);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> TlsSupport.createContext(missing));
        assertTrue(e.getMessage().startsWith("Cannot initialise TLS"), e.getMessage());
    }
}
