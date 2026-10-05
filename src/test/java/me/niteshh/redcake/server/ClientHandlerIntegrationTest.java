package me.niteshh.redcake.server;

import me.niteshh.redcake.command.CommandHandler;
import me.niteshh.redcake.command.commands.reads.ExistsCommand;
import me.niteshh.redcake.command.commands.reads.GetCommand;
import me.niteshh.redcake.command.commands.reads.InfoCommand;
import me.niteshh.redcake.command.commands.reads.PingCommand;
import me.niteshh.redcake.command.commands.write.IncrCommand;
import me.niteshh.redcake.command.commands.write.SetCommand;
import me.niteshh.redcake.config.RedCakeAuthConfig;
import me.niteshh.redcake.replication.ReplicationConfig;
import me.niteshh.redcake.replication.ReplicationManager;
import me.niteshh.redcake.replication.ReplicationRole;
import me.niteshh.redcake.replication.RespCommandCodec;
import me.niteshh.redcake.replication.primary.ReplicaManager;
import me.niteshh.redcake.replication.replica.PrimaryConnection;
import me.niteshh.redcake.replication.replica.ReplicaSyncManager;
import me.niteshh.redcake.resp.RespParser;
import me.niteshh.redcake.resp.RespWriter;
import me.niteshh.redcake.store.ExpirationManager;
import me.niteshh.redcake.store.InMemoryKeyValueStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ClientHandlerIntegrationTest {

    private ExpirationManager expirationManager;
    private InMemoryKeyValueStore store;

    @BeforeEach
    void setUp() {
        expirationManager = new ExpirationManager();
        store = new InMemoryKeyValueStore(expirationManager);
        store.initializeExpirationHandler();
        expirationManager.start();
    }

    @AfterEach
    void tearDown() {
        expirationManager.stop();
    }

    @Test
    void executesCommandsOverSocketAndKeepsConnectionOpen()
            throws Exception {
        ClientHandler handler = createHandler(
                new RedCakeAuthConfig(null),
                ReplicationRole.PRIMARY
        );

        try (Connection connection = connectTo(handler)) {
            send(connection, "SET", "name", "redCake");
            assertResponse(connection, "+OK\r\n");

            send(connection, "GET", "name");
            assertResponse(connection, "$7\r\nredCake\r\n");

            send(connection, "EXISTS", "name");
            assertResponse(connection, ":1\r\n");
        }
    }

    @Test
    void requiresValidAuthenticationBeforeExecutingCommands()
            throws Exception {
        ClientHandler handler = createHandler(
                new RedCakeAuthConfig("secret"),
                ReplicationRole.PRIMARY
        );

        try (Connection connection = connectTo(handler)) {
            send(connection, "PING");
            assertResponse(connection, "-NOAUTH authentication required\r\n");

            send(connection, "AUTH", "wrong");
            assertResponse(connection, "-WRONGPASS invalid username-password pair or user is disabled\r\n");

            send(connection, "AUTH", "secret");
            assertResponse(connection, "+OK\r\n");

            send(connection, "PING");
            assertResponse(connection, "+PONG\r\n");
        }
    }

    @Test
    void rejectsWritesWhenRunningAsReplica()
            throws Exception {
        ClientHandler handler = createHandler(
                new RedCakeAuthConfig(null),
                ReplicationRole.REPLICA
        );

        try (Connection connection = connectTo(handler)) {
            send(connection, "SET", "name", "redCake");
            assertResponse(connection, "-READONLY replica does not accept writes\r\n");

            send(connection, "GET", "name");
            assertResponse(connection, "$-1\r\n");
        }
    }

    @Test
    void returnsServerReplicationAndKeyspaceInfo()
            throws Exception {
        ClientHandler handler = createHandler(
                new RedCakeAuthConfig(null),
                ReplicationRole.PRIMARY
        );

        try (Connection connection = connectTo(handler)) {
            send(connection, "SET", "name", "redCake");
            assertResponse(connection, "+OK\r\n");

            send(connection, "INFO");
            String response = readLineResponse(connection);

            assertTrue(response.startsWith("$"));
            assertTrue(response.contains("# Server\r\n"));
            assertTrue(response.contains("redcake_version:0.2.0\r\n"));
            assertTrue(response.contains("# Replication\r\n"));
            assertTrue(response.contains("role:primary\r\n"));
            assertTrue(response.matches("(?s).*master_replid:[0-9a-f]{40}\\r\\n.*"));
            assertTrue(response.contains("master_repl_offset:0\r\n"));
            assertTrue(response.contains("# Keyspace\r\n"));
            assertTrue(response.contains("db0:keys=1,expires=0\r\n"));
        }
    }

    @Test
    void handlesConcurrentUsersAndRequestsWithoutLostUpdates()
            throws Exception {
        ClientHandler handler = createHandler(
                new RedCakeAuthConfig(null),
                ReplicationRole.PRIMARY
        );
        int userCount = 8;
        int requestsPerUser = 100;
        CountDownLatch usersReady = new CountDownLatch(userCount);
        CountDownLatch startRequests = new CountDownLatch(1);

        try (ExecutorService users = java.util.concurrent.Executors
                .newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<?>> requests = new java.util.ArrayList<>();

            for (int user = 0; user < userCount; user++) {
                requests.add(users.submit(() -> {
                    try (Connection connection = connectTo(handler)) {
                        usersReady.countDown();
                        assertTrue(
                                startRequests.await(2, TimeUnit.SECONDS),
                                "concurrent request barrier was not released"
                        );

                        for (int request = 0;
                             request < requestsPerUser;
                             request++) {
                            send(connection, "INCR", "concurrent-counter");
                            String response = readLineResponse(connection);
                            assertTrue(
                                    response.startsWith(":"),
                                    "unexpected response: " + response
                            );
                        }
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                }));
            }

            assertTrue(
                    usersReady.await(5, TimeUnit.SECONDS),
                    "not all concurrent users connected"
            );
            startRequests.countDown();

            for (java.util.concurrent.Future<?> request : requests) {
                request.get(10, TimeUnit.SECONDS);
            }
        }

        try (Connection connection = connectTo(handler)) {
            send(connection, "GET", "concurrent-counter");
            assertEquals(
                    "$" + String.valueOf(userCount * requestsPerUser).length()
                            + "\r\n"
                            + userCount * requestsPerUser
                            + "\r\n",
                    readLineResponse(connection)
            );
        }
    }

    private ClientHandler createHandler(
            RedCakeAuthConfig authConfig,
            ReplicationRole role
    ) {
        ReplicationManager replicationManager = new ReplicationManager(
                new ReplicationConfig(role, "127.0.0.1", 6379),
                new ReplicaManager(),
                mock(ReplicaSyncManager.class),
                store
        );
        CommandHandler commandHandler = new CommandHandler(
                List.of(
                        new PingCommand(),
                        new SetCommand(store),
                        new GetCommand(store),
                        new ExistsCommand(store),
                        new InfoCommand(
                                store,
                                new ReplicationConfig(
                                        role,
                                        "127.0.0.1",
                                        6379
                                )
                        ),
                        new IncrCommand(store)
                )
        );

        return new ClientHandler(
                new RespParser(),
                commandHandler,
                new RespWriter(),
                replicationManager,
                authConfig
        );
    }

    private Connection connectTo(ClientHandler handler)
            throws Exception {
        ServerSocket server = new ServerSocket(0);
        CountDownLatch handlerStarted = new CountDownLatch(1);
        Thread handlerThread = Thread.ofPlatform().start(() -> {
            try (Socket socket = server.accept()) {
                server.close();
                handlerStarted.countDown();
                handler.handleClient(socket);
            } catch (IOException e) {
                throw new RuntimeException(e);
            } finally {
                try {
                    server.close();
                } catch (IOException ignored) {
                    // The listener is already closed after accepting the client.
                }
            }
        });

        Socket client = new Socket(
                "127.0.0.1",
                server.getLocalPort()
        );
        assertTrue(
                handlerStarted.await(2, TimeUnit.SECONDS),
                "client handler did not start"
        );
        return new Connection(client, handlerThread);
    }

    private void send(Connection connection, String... command)
            throws IOException {
        OutputStream output = connection.socket().getOutputStream();
        output.write(RespCommandCodec.encode(List.of(command)));
        output.flush();
    }

    private void assertResponse(
            Connection connection,
            String expected
    ) throws IOException {
        byte[] actual = connection.socket()
                .getInputStream()
                .readNBytes(expected.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(
                expected,
                new String(actual, StandardCharsets.UTF_8)
        );
    }

    private String readLineResponse(Connection connection)
            throws IOException {
        InputStream input = connection.socket().getInputStream();
        StringBuilder response = new StringBuilder();

        int firstByte = input.read();
        if (firstByte == '$') {
            response.append((char) firstByte);
            response.append(readUntilCrLf(input));
            int length = Integer.parseInt(response.substring(1, response.length() - 2));
            byte[] value = input.readNBytes(length + 2);
            if (value.length != length + 2) {
                throw new IOException("Unexpected end of RESP bulk string");
            }
            response.append(new String(value, StandardCharsets.UTF_8));
            return response.toString();
        }

        response.append((char) firstByte);
        response.append(readUntilCrLf(input));
        return response.toString();
    }

    private String readUntilCrLf(InputStream input)
            throws IOException {
        StringBuilder line = new StringBuilder();
        int previous = -1;
        int current;
        while ((current = input.read()) != -1) {
            line.append((char) current);
            if (previous == '\r' && current == '\n') {
                return line.toString();
            }
            previous = current;
        }
        throw new IOException("Unexpected end of RESP response");
    }

    private record Connection(
            Socket socket,
            Thread handlerThread
    ) implements AutoCloseable {

        @Override
        public void close() throws Exception {
            socket.close();
            handlerThread.join(2_000);
            assertTrue(
                    !handlerThread.isAlive(),
                    "client handler did not stop"
            );
        }
    }
}
