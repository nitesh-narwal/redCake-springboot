package me.niteshh.redcake.server;

import lombok.extern.slf4j.Slf4j;
import me.niteshh.redcake.command.CommandHandler;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.config.MemoryConfig;
import me.niteshh.redcake.config.RedCakeAuthConfig;
import me.niteshh.redcake.persistence.AppendOnlyLog;
import me.niteshh.redcake.pubsub.PubSubBroker;
import me.niteshh.redcake.replication.ReplicationManager;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.security.AclRegistry;
import me.niteshh.redcake.security.Role;
import me.niteshh.redcake.stats.CommandStats;
import me.niteshh.redcake.stats.ServerStats;
import me.niteshh.redcake.stats.SlowLog;
import me.niteshh.redcake.store.MemoryManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Serves one client connection from first byte to disconnect. Runs on its own
 * virtual thread, so plain blocking I/O scales to thousands of connections.
 *
 * <h3>Per-command pipeline ({@link #process})</h3>
 * <ol>
 *   <li>Parse one command (RESP array or inline text).</li>
 *   <li>{@code QUIT}, {@code AUTH}: allowed before login. Everything else needs login
 *       when an API key or ACL users are configured.</li>
 *   <li>Guards: a replica that is still loading its snapshot answers {@code -LOADING};
 *       a subscribed client may only (un)subscribe/PING; the user's {@link Role}
 *       must allow the command ({@code -NOPERM}).</li>
 *   <li>Transactions: inside {@code MULTI} commands are queued; {@code EXEC} runs
 *       them atomically.</li>
 *   <li>Connection-level commands handled here because they need the session or
 *       several subsystems: {@code PSYNC/REPLCONF/REPLICAOF/WAIT/BGREWRITEAOF},
 *       {@code (P)SUBSCRIBE/(P)UNSUBSCRIBE}, {@code CLIENT}, {@code ACL}, {@code MONITOR}.</li>
 *   <li>Everything else goes through {@link #execute}: <b>reads</b> run lock-free and in
 *       parallel; <b>writes</b> on a primary run inside the global write lock:
 *       make room (maxmemory) -&gt; normalize -&gt; execute -&gt; AOF -&gt; queue for replicas.
 *       Writes on a replica are rejected with {@code -READONLY}.</li>
 * </ol>
 *
 * <h3>Performance: pipelining</h3>
 * Replies are buffered and flushed only when no further request bytes are
 * waiting ({@code input.available() == 0}), so N pipelined commands cost one
 * TCP write.
 *
 * <h3>Robustness</h3>
 * Malformed protocol: {@code -ERR Protocol error} then close. A bug in one
 * command yields {@code -ERR internal error} and keeps the connection. Idle
 * clients are cut by the socket timeout set by the server.
 */
@Slf4j
@Component
public class ClientHandler {

    /** Unauthenticated connections must log in within this time. */
    private static final int AUTH_TIMEOUT_MILLIS = 15_000;
    /** Disconnect after this many failed AUTH attempts on one connection. */
    private static final int MAX_FAILURES_PER_CONNECTION = 10;

    /** Commands implemented in this class that only admins may run. */
    private static final Set<String> ADMIN_BUILTINS =
            Set.of("PSYNC", "REPLCONF", "BGREWRITEAOF", "REPLICAOF", "SLAVEOF", "MONITOR");
    private static final Set<String> SUBSCRIBED_ALLOWED =
            Set.of("SUBSCRIBE", "UNSUBSCRIBE", "PSUBSCRIBE", "PUNSUBSCRIBE", "PING", "QUIT");
    /** Still answered while a replica is loading its snapshot. */
    private static final Set<String> LOADING_SAFE = Set.of(
            "PING", "INFO", "AUTH", "ROLE", "REPLICAOF", "SLAVEOF", "QUIT",
            "CONFIG", "CLIENT", "COMMAND", "SLOWLOG", "ACL", "HELLO");

    private final RespParser parser;
    private final CommandHandler commandHandler;
    private final RespWriter respWriter;
    private final ReplicationManager replicationManager;
    private final RedCakeAuthConfig authConfig;
    private final ServerStats serverStats;
    private final AppendOnlyLog appendOnlyLog;
    private final AuthFailureTracker authFailureTracker;
    private final AclRegistry aclRegistry;
    private final MemoryManager memoryManager;
    private final PubSubBroker pubSubBroker;
    private final ClientRegistry clientRegistry;
    private final SlowLog slowLog;
    private final CommandStats commandStats;

    @Autowired
    public ClientHandler(
            RespParser parser,
            CommandHandler commandHandler,
            RespWriter respWriter,
            ReplicationManager replicationManager,
            RedCakeAuthConfig authConfig,
            ServerStats serverStats,
            AppendOnlyLog appendOnlyLog,
            AuthFailureTracker authFailureTracker,
            AclRegistry aclRegistry,
            MemoryManager memoryManager,
            PubSubBroker pubSubBroker,
            ClientRegistry clientRegistry,
            SlowLog slowLog,
            CommandStats commandStats
    ) {
        this.parser = parser;
        this.commandHandler = commandHandler;
        this.respWriter = respWriter;
        this.replicationManager = replicationManager;
        this.authConfig = authConfig;
        this.serverStats = serverStats;
        this.appendOnlyLog = appendOnlyLog;
        this.authFailureTracker = authFailureTracker;
        this.aclRegistry = aclRegistry;
        this.memoryManager = memoryManager;
        this.pubSubBroker = pubSubBroker;
        this.clientRegistry = clientRegistry;
        this.slowLog = slowLog;
        this.commandStats = commandStats;
    }

    /** Convenience constructor with default (inert) collaborators; used by unit tests. */
    public ClientHandler(
            RespParser parser,
            CommandHandler commandHandler,
            RespWriter respWriter,
            ReplicationManager replicationManager,
            RedCakeAuthConfig authConfig
    ) {
        this(parser, commandHandler, respWriter, replicationManager, authConfig,
                new ServerStats(), AppendOnlyLog.disabled(), new AuthFailureTracker(),
                AclRegistry.empty(),
                new MemoryManager(replicationManager.getStore(), MemoryConfig.unlimited(), new ServerStats()),
                new PubSubBroker(), new ClientRegistry(), new SlowLog(), new CommandStats());
    }

    private boolean authRequired() {
        return authConfig.isEnabled() || aclRegistry.isEnabled();
    }

    /**
     * Serves {@code clientSocket} until the peer disconnects, sends QUIT,
     * violates the protocol, times out or the server shuts down. Always
     * closes the socket.
     */
    public void handleClient(Socket clientSocket) {
        Session session = null;
        serverStats.clientConnected();

        try (clientSocket) {
            int idleTimeoutMillis = clientSocket.getSoTimeout();
            BufferedInputStream input = new BufferedInputStream(clientSocket.getInputStream(), 16 * 1024);
            ConnectionOutput output = new ConnectionOutput(
                    new BufferedOutputStream(clientSocket.getOutputStream(), 16 * 1024), respWriter);

            String address = remoteAddress(clientSocket);
            session = new Session(clientRegistry.nextId(), clientSocket, output, address, ipOf(address));
            session.authenticated = !authRequired();
            clientRegistry.register(session);

            if (!session.authenticated) {
                clientSocket.setSoTimeout(
                        idleTimeoutMillis == 0
                                ? AUTH_TIMEOUT_MILLIS
                                : Math.min(idleTimeoutMillis, AUTH_TIMEOUT_MILLIS));
            }

            try {
                serve(session, input, idleTimeoutMillis);
            } catch (ProtocolException e) {
                // Tell the client what it did wrong, then drop it.
                output.write(new ErrorValue("Protocol error: " + e.getMessage()));
            } finally {
                try {
                    output.flush();
                } catch (IOException ignored) {
                    // peer already gone
                }
            }
        } catch (SocketTimeoutException e) {
            log.debug("Client timed out");
        } catch (IOException e) {
            log.debug("Client connection ended: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error handling client", e);
        } finally {
            if (session != null) {
                cleanUp(session);
            }
            serverStats.clientDisconnected();
        }
    }

    /** Releases everything a connection may hold: subscriptions, replica registration, registry entry. */
    private void cleanUp(Session session) {
        for (String channel : session.channels) {
            pubSubBroker.unsubscribe(channel, session);
        }
        for (String pattern : session.patterns) {
            pubSubBroker.punsubscribe(pattern, session);
        }
        if (session.attachedReplica != null) {
            replicationManager.unregisterReplica(session.attachedReplica);
        }
        clientRegistry.unregister(session);
    }

    /** The command loop; returns on disconnect/QUIT, throws on protocol or I/O errors. */
    private void serve(Session session, BufferedInputStream input, int idleTimeoutMillis) throws IOException {
        while (true) {
            List<String> command = parser.parseClientCommand(input);
            if (command == null) {
                return;
            }
            serverStats.commandProcessed();
            String name = command.getFirst().toUpperCase(Locale.ROOT);
            session.lastActiveMillis = System.currentTimeMillis();
            session.lastCommand = name;

            // A connected replica only ever sends acknowledgements; never reply to it.
            if (session.attachedReplica != null) {
                handleReplicaLink(session, command);
                continue;
            }

            RespValue response = process(session, command, name, idleTimeoutMillis);
            if (response != null) {
                session.out.write(response);
            }
            if (input.available() == 0) {
                session.out.flush();
            }
            if (session.closeRequested || session.failedLogins >= MAX_FAILURES_PER_CONNECTION) {
                return;
            }
        }
    }

    /**
     * Runs the pipeline described in the class comment.
     *
     * @return the reply, or {@code null} if the method already wrote its replies
     */
    private RespValue process(Session s, List<String> command, String name, int idleTimeoutMillis)
            throws IOException {
        if ("QUIT".equals(name)) {
            s.closeRequested = true;
            return new SimpleString("OK");
        }
        if ("AUTH".equals(name)) {
            return authenticate(s, command, idleTimeoutMillis);
        }
        if (!s.authenticated) {
            if ("PSYNC".equals(name)) {
                s.closeRequested = true;
            }
            return new ErrorValue("NOAUTH authentication required");
        }

        RedCakeCommand known = commandHandler.find(name);

        if (replicationManager.isReplica()
                && replicationManager.getStats().isLoading()
                && !LOADING_SAFE.contains(name)) {
            return new ErrorValue("LOADING the replica is loading its dataset from the primary");
        }
        if (s.isSubscribed() && !SUBSCRIBED_ALLOWED.contains(name)) {
            return new ErrorValue("Can't execute '" + name.toLowerCase(Locale.ROOT)
                    + "': only (P)SUBSCRIBE / (P)UNSUBSCRIBE / PING / QUIT are allowed in this context");
        }

        boolean admin = ADMIN_BUILTINS.contains(name) || (known != null && known.isAdmin());
        boolean write = known != null && known.isWrite();
        if (!s.role.allows(admin, write)) {
            return new ErrorValue("NOPERM this user has no permissions to run the '"
                    + name.toLowerCase(Locale.ROOT) + "' command");
        }

        switch (name) {
            case "MULTI" -> {
                return multi(s);
            }
            case "EXEC" -> {
                return exec(s);
            }
            case "DISCARD" -> {
                if (!s.inMulti) {
                    return new ErrorValue("DISCARD without MULTI");
                }
                s.resetTransaction();
                return new SimpleString("OK");
            }
            case "WATCH" -> {
                return watch(s, command);
            }
            case "UNWATCH" -> {
                s.watched.clear();
                return new SimpleString("OK");
            }
            default -> {
                // fall through to queueing / dispatch
            }
        }

        if (s.inMulti) {
            return queue(s, command, known);
        }
        return dispatch(s, command, name);
    }

    /** Connection-level commands, then ordinary command execution. */
    private RespValue dispatch(Session s, List<String> command, String name) throws IOException {
        return switch (name) {
            case "PSYNC" -> psync(s, command);
            case "REPLCONF" -> replconf(s, command);
            case "REPLICAOF", "SLAVEOF" -> replicaOf(command);
            case "WAIT" -> waitForReplicas(command);
            case "BGREWRITEAOF" -> rewriteAof();
            case "SUBSCRIBE", "UNSUBSCRIBE", "PSUBSCRIBE", "PUNSUBSCRIBE" -> subscription(s, command, name);
            case "PING" -> s.isSubscribed()
                    ? new ArrayValue(List.of(new BulkString("pong"), new BulkString(
                    command.size() > 1 ? command.get(1) : "")))
                    : timedExecute(s, command, name);
            case "CLIENT" -> client(s, command);
            case "ACL" -> acl(s, command);
            case "MONITOR" -> monitor(s);
            default -> timedExecute(s, command, name);
        };
    }

    // ---------------------------------------------------------------- AUTH

    /**
     * {@code AUTH key} or {@code AUTH user key}. A failed attempt never
     * changes an already authenticated session, adds a growing delay, and
     * counts toward a per-IP lockout ({@link AuthFailureTracker}). The user
     * {@code default} is the API key (admin); other users come from the ACL file.
     */
    private RespValue authenticate(Session s, List<String> command, int idleTimeoutMillis) {
        if (!authRequired()) {
            return new ErrorValue(
                    "AUTH <password> called without any password configured for the default user"
            );
        }
        if (command.size() < 2 || command.size() > 3) {
            return new ErrorValue("wrong number of arguments for 'auth' command");
        }
        if (authFailureTracker.isLockedOut(s.ip)) {
            return new ErrorValue("ERR too many failed authentication attempts; try again later");
        }

        String user = command.size() == 3 ? command.get(1) : "default";
        String password = command.getLast();

        Role role = null;
        if ("default".equals(user)) {
            if (authConfig.matches(password)) {
                role = Role.ADMIN;
            }
        } else {
            role = aclRegistry.authenticate(user, password);
        }

        if (role != null) {
            authFailureTracker.reset(s.ip);
            s.authenticated = true;
            s.user = user;
            s.role = role;
            s.failedLogins = 0;
            try {
                s.socket.setSoTimeout(idleTimeoutMillis);
            } catch (java.net.SocketException ignored) {
                // socket closing; the next read will report it
            }
            return new SimpleString("OK");
        }

        authFailureTracker.recordFailure(s.ip);
        serverStats.authenticationFailed();
        s.failedLogins++;
        pause(50L * Math.min(s.failedLogins, 20)); // slow down guessing
        return new ErrorValue("WRONGPASS invalid username-password pair or user is disabled");
    }

    // --------------------------------------------------------- replication

    /**
     * {@code PSYNC <replid> <offset>}: turn this connection into a replication
     * link. Pending output is flushed first because the replica connection
     * writes to the same socket through its own buffer.
     */
    private RespValue psync(Session s, List<String> command) throws IOException {
        if (!replicationManager.isPrimary()) {
            return new ErrorValue("a replica cannot serve PSYNC (replica chaining is not supported)");
        }
        long offset;
        try {
            if (command.size() != 3) {
                throw new NumberFormatException();
            }
            offset = Long.parseLong(command.get(2));
        } catch (NumberFormatException e) {
            return new ErrorValue("usage: PSYNC <replid> <offset>");
        }

        s.out.flush();
        s.attachedReplica = replicationManager.registerReplica(
                s.socket, command.get(1), offset, s.replicaPort);
        s.socket.setSoTimeout(0); // replicas are silent; never time them out
        return null;
    }

    /** {@code REPLCONF listening-port <port>} / {@code REPLCONF capa psync2}. */
    private RespValue replconf(Session s, List<String> command) {
        if (command.size() == 3 && "listening-port".equalsIgnoreCase(command.get(1))) {
            try {
                int port = Integer.parseInt(command.get(2));
                if (port >= 1 && port <= 65535) {
                    s.replicaPort = port;
                    return new SimpleString("OK");
                }
            } catch (NumberFormatException ignored) {
                // fall through to the error
            }
        } else if (command.size() == 3
                && "capa".equalsIgnoreCase(command.get(1))
                && "psync2".equalsIgnoreCase(command.get(2))) {
            return new SimpleString("OK"); // PSYNC2 is the only capability RedCake understands
        }
        return new ErrorValue("unrecognized REPLCONF option");
    }

    /** What a replica may send on its stream connection: only {@code REPLCONF ACK <offset>}. */
    private void handleReplicaLink(Session s, List<String> command) {
        if (command.size() == 3
                && "REPLCONF".equalsIgnoreCase(command.get(0))
                && "ACK".equalsIgnoreCase(command.get(1))) {
            try {
                s.attachedReplica.recordAck(Long.parseLong(command.get(2)));
            } catch (NumberFormatException ignored) {
                // malformed ack: ignore
            }
        }
    }

    /** {@code REPLICAOF host port} or {@code REPLICAOF NO ONE}. */
    private RespValue replicaOf(List<String> command) {
        if (command.size() != 3) {
            return new ErrorValue("wrong number of arguments for 'replicaof' command");
        }
        try {
            if ("NO".equalsIgnoreCase(command.get(1)) && "ONE".equalsIgnoreCase(command.get(2))) {
                if (replicationManager.isReplica()) {
                    replicationManager.promoteToPrimary();
                    replicationManager.lockPrimaryWrites();
                    try {
                        appendOnlyLog.activateFromSnapshot();
                    } finally {
                        replicationManager.unlockPrimaryWrites();
                    }
                }
                return new SimpleString("OK");
            }

            int port = Integer.parseInt(command.get(2));
            if (port < 1 || port > 65535) {
                return new ErrorValue("Invalid master port");
            }
            replicationManager.becomeReplicaOf(command.get(1), port);
            appendOnlyLog.deactivate();
            return new SimpleString("OK");
        } catch (NumberFormatException e) {
            return new ErrorValue("Invalid master port");
        } catch (IOException e) {
            return new ErrorValue("MISCONF could not start the append-only file: " + e.getMessage());
        }
    }

    /** {@code WAIT numreplicas timeout-ms}: how many replicas acknowledged all writes so far. */
    private RespValue waitForReplicas(List<String> command) {
        if (command.size() != 3) {
            return new ErrorValue("wrong number of arguments for 'wait' command");
        }
        try {
            int count = Integer.parseInt(command.get(1));
            long timeout = Long.parseLong(command.get(2));
            if (count < 0 || timeout < 0) {
                return new ErrorValue("value is not an integer or out of range");
            }
            if (!replicationManager.isPrimary()) {
                return new IntegerValue(0);
            }
            return new IntegerValue(replicationManager.waitForReplicas(count, timeout));
        } catch (NumberFormatException e) {
            return new ErrorValue("value is not an integer or out of range");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ErrorValue("interrupted");
        }
    }

    /** {@code BGREWRITEAOF}: compacts the AOF. Runs synchronously under the write lock. */
    private RespValue rewriteAof() {
        if (!replicationManager.isPrimary() || !appendOnlyLog.isActive()) {
            return new ErrorValue("AOF is not enabled on this node");
        }
        replicationManager.lockPrimaryWrites();
        try {
            long keys = appendOnlyLog.rewrite();
            return new SimpleString("Append only file rewritten (" + keys + " keys)");
        } catch (IOException e) {
            return new ErrorValue("AOF rewrite failed: " + e.getMessage());
        } finally {
            replicationManager.unlockPrimaryWrites();
        }
    }

    // --------------------------------------------------------- transactions

    private RespValue multi(Session s) {
        if (s.inMulti) {
            return new ErrorValue("MULTI calls can not be nested");
        }
        s.inMulti = true;
        return new SimpleString("OK");
    }

    /** Queues a command inside MULTI; problems detectable now poison the transaction. */
    private RespValue queue(Session s, List<String> command, RedCakeCommand known) {
        if (known == null) {
            s.multiDirty = true;
            return new ErrorValue("unknown command '" + command.getFirst().toUpperCase(Locale.ROOT) + "'");
        }
        if (known.isWrite() && replicationManager.isReplica()) {
            s.multiDirty = true;
            return new ErrorValue("READONLY replica does not accept writes");
        }
        s.queued.add(command);
        return new SimpleString("QUEUED");
    }

    private RespValue watch(Session s, List<String> command) {
        if (s.inMulti) {
            return new ErrorValue("WATCH inside MULTI is not allowed");
        }
        if (command.size() < 2) {
            return new ErrorValue("wrong number of arguments for 'watch' command");
        }
        for (String key : command.subList(1, command.size())) {
            s.watched.putIfAbsent(key, replicationManager.getStore().versionOf(key));
        }
        return new SimpleString("OK");
    }

    /**
     * {@code EXEC}: runs the queued commands as one unit. The write lock is
     * held for the whole batch, so no other writer can interleave and the
     * commands are contiguous in the AOF and replication stream. If a
     * watched key changed since {@code WATCH}, nothing runs and a null array
     * is returned (optimistic locking).
     */
    private RespValue exec(Session s) {
        if (!s.inMulti) {
            return new ErrorValue("EXEC without MULTI");
        }
        List<List<String>> queued = new ArrayList<>(s.queued);
        boolean dirty = s.multiDirty;
        var watched = new java.util.HashMap<>(s.watched);
        s.resetTransaction();

        if (dirty) {
            return new ErrorValue("EXECABORT Transaction discarded because of previous errors.");
        }

        replicationManager.lockPrimaryWrites();
        try {
            for (var entry : watched.entrySet()) {
                if (replicationManager.getStore().versionOf(entry.getKey()) != entry.getValue()) {
                    return new NullArray();
                }
            }
            List<RespValue> results = new ArrayList<>(queued.size());
            for (List<String> queuedCommand : queued) {
                String name = queuedCommand.getFirst().toUpperCase(Locale.ROOT);
                results.add(timedExecute(s, queuedCommand, name));
            }
            return new ArrayValue(results);
        } finally {
            replicationManager.unlockPrimaryWrites();
        }
    }

    // ------------------------------------------------------------ pub/sub

    /**
     * {@code SUBSCRIBE/UNSUBSCRIBE/PSUBSCRIBE/PUNSUBSCRIBE}. Each channel gets
     * its own confirmation array, as RESP2 clients expect. Subscribed
     * connections are never idle-timed-out.
     */
    private RespValue subscription(Session s, List<String> command, String name) throws IOException {
        List<String> targets = command.subList(1, command.size());
        boolean pattern = name.startsWith("P");
        boolean subscribe = !name.contains("UNSUBSCRIBE");

        if (subscribe && targets.isEmpty()) {
            return new ErrorValue("wrong number of arguments for '" + name.toLowerCase(Locale.ROOT) + "' command");
        }

        var set = pattern ? s.patterns : s.channels;
        String kind = name.toLowerCase(Locale.ROOT);
        List<String> words = subscribe || !targets.isEmpty() ? targets : new ArrayList<>(set);

        if (words.isEmpty()) { // UNSUBSCRIBE with nothing to unsubscribe from
            s.out.write(new ArrayValue(List.of(
                    new BulkString(kind), new NullValue(), new IntegerValue(s.subscriptionCount()))));
            return null;
        }

        for (String word : words) {
            if (subscribe) {
                if (set.add(word)) {
                    if (pattern) {
                        pubSubBroker.psubscribe(word, s);
                    } else {
                        pubSubBroker.subscribe(word, s);
                    }
                }
            } else if (set.remove(word)) {
                if (pattern) {
                    pubSubBroker.punsubscribe(word, s);
                } else {
                    pubSubBroker.unsubscribe(word, s);
                }
            }
            s.out.write(new ArrayValue(List.of(
                    new BulkString(kind), new BulkString(word), new IntegerValue(s.subscriptionCount()))));
        }
        if (s.isSubscribed()) {
            s.socket.setSoTimeout(0);
        }
        return null;
    }

    // ------------------------------------------------------ introspection

    /** {@code CLIENT ID|SETNAME|GETNAME|LIST|INFO|KILL|SETINFO}. */
    private RespValue client(Session s, List<String> command) {
        if (command.size() < 2) {
            return new ErrorValue("wrong number of arguments for 'client' command");
        }
        String sub = command.get(1).toUpperCase(Locale.ROOT);
        return switch (sub) {
            case "ID" -> new IntegerValue(s.id);
            case "SETNAME" -> {
                if (command.size() != 3 || command.get(2).contains(" ")) {
                    yield new ErrorValue("Client names cannot contain spaces, newlines or special characters.");
                }
                s.name = command.get(2);
                yield new SimpleString("OK");
            }
            case "GETNAME" -> s.name.isEmpty() ? new NullValue() : new BulkString(s.name);
            case "SETINFO" -> new SimpleString("OK");
            case "INFO" -> new BulkString(clientRegistry.describe(s, System.currentTimeMillis()) + "\n");
            case "LIST" -> new BulkString(clientRegistry.describeAll());
            case "KILL" -> {
                if (!s.role.allows(true, false)) {
                    yield new ErrorValue("NOPERM this user has no permissions to run the 'client|kill' command");
                }
                if (command.size() == 4 && "ID".equalsIgnoreCase(command.get(2))) {
                    yield new IntegerValue(clientRegistry.kill(command.get(3)));
                }
                if (command.size() == 3) {
                    int killed = clientRegistry.kill(command.get(2));
                    yield killed > 0 ? new SimpleString("OK") : new ErrorValue("No such client");
                }
                yield CommandSupport.SYNTAX_ERROR;
            }
            default -> new ErrorValue("unknown subcommand '" + command.get(1) + "' for 'client' command");
        };
    }

    /** {@code ACL WHOAMI|LIST|USERS}. */
    private RespValue acl(Session s, List<String> command) {
        if (command.size() < 2) {
            return new ErrorValue("wrong number of arguments for 'acl' command");
        }
        return switch (command.get(1).toUpperCase(Locale.ROOT)) {
            case "WHOAMI" -> new BulkString(s.user);
            case "USERS" -> {
                List<String> names = new ArrayList<>(aclRegistry.usernames());
                names.addFirst("default");
                yield requireAdmin(s, ArrayValue.ofBulkStrings(names));
            }
            case "LIST" -> {
                List<String> lines = new ArrayList<>();
                lines.add("default " + (authConfig.isEnabled() ? "admin (api key)" : "admin (no password)"));
                lines.addAll(aclRegistry.describe());
                yield requireAdmin(s, ArrayValue.ofBulkStrings(lines));
            }
            default -> new ErrorValue("unknown subcommand '" + command.get(1) + "' for 'acl' command");
        };
    }

    private RespValue requireAdmin(Session s, RespValue ifAllowed) {
        return s.role == Role.ADMIN
                ? ifAllowed
                : new ErrorValue("NOPERM this user has no permissions to run the 'acl' command");
    }

    private RespValue monitor(Session s) throws IOException {
        clientRegistry.addMonitor(s);
        s.socket.setSoTimeout(0);
        return new SimpleString("OK");
    }

    // ------------------------------------------------------------ commands

    /** Runs a normal command with timing, slow-log, per-command stats and MONITOR feed. */
    private RespValue timedExecute(Session s, List<String> command, String name) {
        long start = System.nanoTime();
        RespValue response = execute(command);
        long micros = (System.nanoTime() - start) / 1_000;

        commandStats.record(name, micros, response instanceof ErrorValue);
        slowLog.record(command, micros, s.address, s.name);
        if (clientRegistry.hasMonitors()) {
            clientRegistry.feedMonitors(s, command);
        }
        return response;
    }

    /** Runs a command, applying the read/write rules described in the class comment. */
    private RespValue execute(List<String> command) {
        try {
            RedCakeCommand known = commandHandler.find(command.getFirst());
            boolean isWrite = known != null && known.isWrite();

            if (!isWrite) {
                return commandHandler.handle(command);
            }
            if (replicationManager.isReplica()) {
                return new ErrorValue("READONLY replica does not accept writes");
            }
            if (!appendOnlyLog.isHealthy()) {
                return new ErrorValue(
                        "MISCONF the append-only file cannot be written; writes are disabled"
                );
            }
            return executeWrite(command, known.growsMemory());
        } catch (RuntimeException e) {
            log.error("Command {} failed unexpectedly", command.getFirst(), e);
            return new ErrorValue("internal error while executing command");
        }
    }

    /**
     * The serialized write path on a primary. Everything between lock and
     * unlock is in-memory only (store update, AOF buffer write, replica
     * queueing); no waiting on replica sockets happens here.
     */
    private RespValue executeWrite(List<String> command, boolean growsMemory) {
        replicationManager.lockPrimaryWrites();
        try {
            // Enforce maxmemory first; evictions are logged as DELs so replicas follow.
            MemoryManager.Outcome room = memoryManager.makeRoom(growsMemory);
            for (String evicted : room.evicted()) {
                propagate(List.of("DEL", evicted));
            }
            if (!room.allowed()) {
                return new ErrorValue("OOM command not allowed when used memory > 'maxmemory'");
            }

            // One clock reading -> identical absolute deadlines everywhere.
            List<String> normalized = commandHandler.normalize(command, System.currentTimeMillis());
            RespValue response = commandHandler.handle(normalized);

            if (!(response instanceof ErrorValue)) {
                IOException failure = propagate(normalized);
                if (failure != null) {
                    response = new ErrorValue("MISCONF failed to persist command: " + failure.getMessage());
                }
            }
            return response;
        } finally {
            replicationManager.unlockPrimaryWrites();
        }
    }

    /**
     * Logs a change to the AOF and queues it for replicas (write lock held).
     * Replication happens even if the disk write failed: memory already changed.
     *
     * @return the AOF failure, or {@code null}
     */
    private IOException propagate(List<String> command) {
        IOException failure = null;
        try {
            appendOnlyLog.append(command);
        } catch (IOException e) {
            failure = e;
        }
        replicationManager.replicate(command);
        return failure;
    }

    // --------------------------------------------------------------- utils

    private static String remoteAddress(Socket socket) {
        return socket.getRemoteSocketAddress() instanceof InetSocketAddress address
                && address.getAddress() != null
                ? address.getAddress().getHostAddress() + ":" + address.getPort()
                : "unknown";
    }

    private static String ipOf(String address) {
        int colon = address.lastIndexOf(':');
        return colon < 0 ? address : address.substring(0, colon);
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
