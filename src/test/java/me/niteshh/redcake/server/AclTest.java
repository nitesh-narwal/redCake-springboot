package me.niteshh.redcake.server;

import me.niteshh.redcake.config.AclConfig;
import me.niteshh.redcake.security.AclRegistry;
import me.niteshh.redcake.security.Role;
import me.niteshh.redcake.support.RespClient;
import me.niteshh.redcake.support.TestServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Named users with roles: file parsing, login, and permission checks. */
class AclTest {

    @TempDir
    Path dir;

    private TestServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private static String hash(String password) {
        return AclRegistry.hash(password.getBytes(StandardCharsets.UTF_8));
    }

    private AclRegistry registry() throws Exception {
        Path file = dir.resolve("users.acl");
        Files.writeString(file, """
                # comment line
                user alice admin     %s
                user bob   readwrite %s

                user carol readonly  %s
                """.formatted(hash("alice-pw"), hash("bob-pw"), hash("carol-pw")));
        return new AclRegistry(new AclConfig(file));
    }

    @Test
    void registryParsesTheFileAndChecksPasswords() throws Exception {
        AclRegistry registry = registry();
        assertTrue(registry.isEnabled());
        assertEquals(Role.ADMIN, registry.authenticate("alice", "alice-pw"));
        assertEquals(Role.READWRITE, registry.authenticate("bob", "bob-pw"));
        assertEquals(Role.READONLY, registry.authenticate("carol", "carol-pw"));
        assertNull(registry.authenticate("alice", "wrong"));
        assertNull(registry.authenticate("nobody", "alice-pw"));
        assertEquals(java.util.List.of("alice admin", "bob readwrite", "carol readonly"), registry.describe());
        assertFalse(AclRegistry.empty().isEnabled());
    }

    @Test
    void registryRejectsBrokenFilesWithLineNumbers() throws Exception {
        String good = hash("x");
        for (String bad : new String[]{
                "user alice admin",                              // too few words
                "usr alice admin " + good,                       // wrong keyword
                "user alice boss " + good,                       // unknown role
                "user alice admin nothex",                       // not hex
                "user alice admin abcd",                         // not 32 bytes
                "user default admin " + good,                    // reserved name
                "user a admin " + good + "\nuser a readonly " + good // duplicate
        }) {
            Path file = dir.resolve("bad.acl");
            Files.writeString(file, bad);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new AclRegistry(new AclConfig(file)), bad);
            assertTrue(e.getMessage().contains("bad.acl"), e.getMessage());
        }
        assertThrows(IllegalArgumentException.class,
                () -> new AclRegistry(new AclConfig(dir.resolve("does-not-exist"))));
    }

    @Test
    void rolesDecideWhoMayDoWhat() throws Exception {
        server = new TestServer().withAcl(registry()).start();

        try (RespClient anonymous = server.client()) {
            assertEquals("-NOAUTH authentication required", anonymous.call("GET", "k"));
            assertTrue(anonymous.call("AUTH", "alice", "nope").startsWith("-WRONGPASS"));
            assertTrue(anonymous.call("AUTH", "ghost", "alice-pw").startsWith("-WRONGPASS"));
        }

        try (RespClient bob = server.client()) {
            assertEquals("+OK", bob.call("AUTH", "bob", "bob-pw"));
            assertEquals("\"bob\"", bob.call("ACL", "WHOAMI"));
            assertEquals("+OK", bob.call("SET", "k", "v"));
            assertEquals("\"v\"", bob.call("GET", "k"));
            assertTrue(bob.call("FLUSHALL").startsWith("-NOPERM"), "readwrite is not admin");
            assertTrue(bob.call("CONFIG", "GET", "maxmemory").startsWith("-NOPERM"));
            assertTrue(bob.call("SLOWLOG", "GET").startsWith("-NOPERM"));
            assertTrue(bob.call("CLIENT", "KILL", "1").startsWith("-NOPERM"));
            assertTrue(bob.call("PSYNC", "?", "-1").startsWith("-NOPERM"), "only admins may replicate");
            assertTrue(bob.call("MONITOR").startsWith("-NOPERM"));
            assertTrue(bob.call("REPLICAOF", "NO", "ONE").startsWith("-NOPERM"));
            assertTrue(bob.call("ACL", "LIST").startsWith("-NOPERM"));
            assertEquals("+PONG", bob.call("PING"));
        }

        try (RespClient carol = server.client()) {
            assertEquals("+OK", carol.call("AUTH", "carol", "carol-pw"));
            assertEquals("\"v\"", carol.call("GET", "k"));
            assertTrue(carol.call("SET", "k", "x").startsWith("-NOPERM"), "readonly cannot write");
            assertTrue(carol.call("DEL", "k").startsWith("-NOPERM"));
            assertTrue(carol.call("HSET", "h", "f", "v").startsWith("-NOPERM"));
            assertEquals(":0", carol.call("EXISTS", "nothing"));
            assertEquals("\"v\"", carol.call("GET", "k"), "nothing was modified");
        }

        try (RespClient alice = server.client()) {
            assertEquals("+OK", alice.call("AUTH", "alice", "alice-pw"));
            assertEquals("+OK", alice.call("SET", "k", "admin-wrote"));
            assertEquals("[\"maxmemory\", \"0\"]", alice.call("CONFIG", "GET", "maxmemory"));
            String users = alice.call("ACL", "LIST");
            assertTrue(users.contains("alice admin") && users.contains("carol readonly"), users);
            assertEquals("[\"default\", \"alice\", \"bob\", \"carol\"]", alice.call("ACL", "USERS"));
            assertEquals("+OK", alice.call("FLUSHALL"));
        }
    }

    @Test
    void permissionsApplyInsideTransactionsToo() throws Exception {
        server = new TestServer().withAcl(registry()).start();
        try (RespClient carol = server.client()) {
            carol.call("AUTH", "carol", "carol-pw");
            assertEquals("+OK", carol.call("MULTI"));
            assertTrue(carol.call("SET", "k", "v").startsWith("-NOPERM"), "checked before queueing");
        }
    }

    @Test
    void apiKeyIsTheDefaultUserAndWorksAlongsideNamedUsers() throws Exception {
        server = new TestServer().withApiKey("master-key").withAcl(registry()).start();
        try (RespClient c = server.client()) {
            assertEquals("+OK", c.call("AUTH", "master-key"));
            assertEquals("\"default\"", c.call("ACL", "WHOAMI"));
            assertEquals("+OK", c.call("FLUSHALL"));
        }
        try (RespClient c = server.client()) {
            assertEquals("+OK", c.call("AUTH", "default", "master-key"));
        }
        try (RespClient c = server.client()) {
            assertEquals("+OK", c.call("AUTH", "bob", "bob-pw"));
            assertEquals("\"bob\"", c.call("ACL", "WHOAMI"));
        }
    }

    @Test
    void withoutAnApiKeyTheDefaultUserCannotLogIn() throws Exception {
        server = new TestServer().withAcl(registry()).start();
        try (RespClient c = server.client()) {
            assertTrue(c.call("AUTH", "anything").startsWith("-WRONGPASS"));
            assertTrue(c.call("PING").startsWith("-NOAUTH"));
        }
    }

    @Test
    void roleMatrix() {
        assertTrue(Role.ADMIN.allows(true, true));
        assertTrue(Role.READWRITE.allows(false, true));
        assertFalse(Role.READWRITE.allows(true, false));
        assertTrue(Role.READONLY.allows(false, false));
        assertFalse(Role.READONLY.allows(false, true));
        assertFalse(Role.READONLY.allows(true, false));
        assertThrows(IllegalArgumentException.class, () -> Role.parse("root"));
    }

    @Test
    void passwordHashMatchesTheDocumentedExample() {
        // sha256("password") - the value used in the AclRegistry Javadoc example
        assertEquals("5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8",
                hash("password"));
    }
}
