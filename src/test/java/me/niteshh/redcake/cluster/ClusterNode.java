package me.niteshh.redcake.cluster;

import me.niteshh.redcake.command.CommandHandler;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.command.commands.reads.InfoCommand;
import me.niteshh.redcake.command.commands.reads.RoleCommand;
import me.niteshh.redcake.config.MemoryConfig;
import me.niteshh.redcake.config.PersistenceConfig;
import me.niteshh.redcake.config.RedCakeAuthConfig;
import me.niteshh.redcake.config.RedCakeServerConfig;
import me.niteshh.redcake.config.TlsConfig;
import me.niteshh.redcake.persistence.AppendOnlyLog;
import me.niteshh.redcake.persistence.ExpiryPropagator;
import me.niteshh.redcake.pubsub.PubSubBroker;
import me.niteshh.redcake.replication.ReplicationConfig;
import me.niteshh.redcake.replication.ReplicationManager;
import me.niteshh.redcake.replication.ReplicationRole;
import me.niteshh.redcake.replication.primary.ReplicaManager;
import me.niteshh.redcake.replication.replica.PrimaryConnection;
import me.niteshh.redcake.replication.replica.ReplicaSyncManager;
import me.niteshh.redcake.resp.RespParser;
import me.niteshh.redcake.resp.RespWriter;
import me.niteshh.redcake.security.AclRegistry;
import me.niteshh.redcake.server.AuthFailureTracker;
import me.niteshh.redcake.server.ClientHandler;
import me.niteshh.redcake.server.ClientRegistry;
import me.niteshh.redcake.server.RedCakeServer;
import me.niteshh.redcake.stats.CommandStats;
import me.niteshh.redcake.stats.ReplicationStats;
import me.niteshh.redcake.stats.ServerStats;
import me.niteshh.redcake.stats.SlowLog;
import me.niteshh.redcake.store.MemoryManager;
import me.niteshh.redcake.support.RespClient;
import me.niteshh.redcake.support.TestSupport;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;

/**
 * One complete RedCake node in this JVM, wired like the Spring context wires it, with the <em>real</em>
 * replication, server and {@link FailoverCoordinator}, talking to a real (embedded) ZooKeeper. Nothing is
 * mocked, so a test that kills the primary exercises the production failover path end to end.
 */
final class ClusterNode implements AutoCloseable {

    /** Short timings keep the tests fast while leaving the same margins as production (lease = 2/3 session). */
    static final int SESSION_MS = 2_000;
    /**
     * The longest session the test ZooKeeper (tickTime 200 ms) grants: 20 ticks. A ZooKeeper client waits 0-1 s
     * before every reconnect attempt (and 1 s more when it knows only one server address), so a session of
     * {@link #SESSION_MS} can run out while the client is merely reconnecting after a connection drop.
     */
    static final int LONG_SESSION_MS = 4_000;
    static final int ELECTION_DELAY_MS = 200;

    final String id;
    final int port;
    final TestSupport.Stack stack = TestSupport.newStack();
    final ReplicationStats replicationStats = new ReplicationStats();
    final ClusterState state;
    final ReplicationConfig replicationConfig;
    final ReplicationManager replication;
    final FailoverCoordinator coordinator;
    final AppendOnlyLog aof;
    final PersistenceConfig persistence;
    final ServerStats serverStats = new ServerStats();
    final CommandStats commandStats = new CommandStats();
    final SlowLog slowLog = new SlowLog();
    final ReplicaManager replicaManager = new ReplicaManager();
    final MemoryConfig memory = MemoryConfig.unlimited();

    private final RedCakeServer server;
    private boolean serverStarted;

    private ClusterNode(Builder b) throws Exception {
        this.id = b.id;
        try (ServerSocket socket = new ServerSocket(0)) {
            this.port = socket.getLocalPort();
        }
        this.persistence = b.aofFile == null ? PersistenceConfig.disabled()
                : new PersistenceConfig(b.aofFile, PersistenceConfig.Fsync.ALWAYS);

        ClusterConfig clusterConfig = new ClusterConfig(b.zkConnect, b.cluster, id, "127.0.0.1", port, b.priority,
                b.sessionMs, 2_000, b.electionDelayMs, b.zkAuth);
        this.state = b.clock == null
                ? new ClusterState(clusterConfig)
                : new ClusterState(true, clusterConfig.leaseMillis(), b.clock);
        this.replicationConfig = new ReplicationConfig(ReplicationRole.REPLICA, null, port, 1024 * 1024, true);
        RedCakeServerConfig serverConfig = new RedCakeServerConfig(port, "127.0.0.1", 100, 0, true);
        RedCakeAuthConfig auth = new RedCakeAuthConfig(null);

        List<RedCakeCommand> commands = TestSupport.baseCommands(stack.store());
        commands.add(new InfoCommand(stack.store(), replicationConfig, serverStats, replicationStats,
                replicaManager, persistence, memory, commandStats, state));
        commands.add(new RoleCommand(replicationConfig, replicationStats, replicaManager));
        CommandHandler handler = new CommandHandler(commands, TestSupport.providers(stack.store()));

        ReplicaSyncManager sync = new ReplicaSyncManager(new PrimaryConnection(), replicationConfig, handler, auth,
                stack.store(), serverConfig, replicationStats);
        this.replication = new ReplicationManager(replicationConfig, replicaManager, sync, stack.store(),
                replicationStats);
        this.aof = new AppendOnlyLog(persistence, handler, stack.store(), replicationConfig);
        new ExpiryPropagator(stack.store(), replication, aof, serverStats).register();

        ClientHandler clientHandler = new ClientHandler(new RespParser(), handler, new RespWriter(), replication,
                auth, serverStats, aof, new AuthFailureTracker(), AclRegistry.empty(),
                new MemoryManager(stack.store(), memory, serverStats), new PubSubBroker(), new ClientRegistry(),
                slowLog, commandStats, state);
        this.server = new RedCakeServer(clientHandler, serverConfig, auth, serverStats, TlsConfig.disabled());
        this.coordinator = b.clock == null
                ? new FailoverCoordinator(clusterConfig, state, new ZooKeeperService(clusterConfig),
                replication, replicationConfig, replicationStats, aof, persistence, stack.store())
                : new FailoverCoordinator(clusterConfig, state, new ZooKeeperService(clusterConfig),
                replication, replicationConfig, replicationStats, aof, persistence, stack.store(), b.clock);

        b.seed.forEach(stack.store()::set);
    }

    static Builder builder(String zkConnect, String cluster, String id) {
        return new Builder(zkConnect, cluster, id);
    }

    static final class Builder {
        private final String zkConnect;
        private final String cluster;
        private final String id;
        private int priority = 100;
        private int electionDelayMs = ELECTION_DELAY_MS;
        private int sessionMs = SESSION_MS;
        private String zkAuth;
        private java.util.function.LongSupplier clock;
        private Path aofFile;
        private final java.util.Map<String, String> seed = new java.util.LinkedHashMap<>();

        private Builder(String zkConnect, String cluster, String id) {
            this.zkConnect = zkConnect;
            this.cluster = cluster;
            this.id = id;
        }

        Builder priority(int value) {
            priority = value;
            return this;
        }

        /** ZooKeeper digest credentials {@code user:password}. */
        Builder zkAuth(String value) {
            zkAuth = value;
            return this;
        }

        /** A monotonic clock with a different origin than {@code System.nanoTime()} (e.g. a negative one). */
        Builder clock(java.util.function.LongSupplier value) {
            clock = value;
            return this;
        }

        Builder electionDelayMs(int value) {
            electionDelayMs = value;
            return this;
        }

        /** For tests in which the session has to survive a connection drop; see {@link ClusterNode#LONG_SESSION_MS}. */
        Builder sessionMs(int value) {
            sessionMs = value;
            return this;
        }

        Builder aof(Path file) {
            aofFile = file;
            return this;
        }

        /** Data the node already holds when it starts, like an AOF replay would leave behind. */
        Builder seed(String key, String value) {
            seed.put(key, value);
            return this;
        }

        ClusterNode build() throws Exception {
            return new ClusterNode(this);
        }
    }

    /** Opens the data port, replays the AOF if any, and joins the cluster. */
    ClusterNode start() throws Exception {
        aof.open();
        server.start();
        serverStarted = true;
        replication.initialize();
        coordinator.start();
        return this;
    }

    RespClient client() throws Exception {
        return new RespClient("127.0.0.1", port);
    }

    boolean isLeader() {
        return state.mode() == ClusterState.Mode.LEADING && state.mayWrite();
    }

    boolean isFollowing() {
        return state.mode() == ClusterState.Mode.FOLLOWING;
    }

    String get(String key) {
        return stack.store().get(key);
    }

    /** The node dies: the data port closes, nothing is handed over, ZooKeeper is not told. */
    void crash() {
        coordinator.abort();
        stopServing();
    }

    /** Orderly shutdown, like {@code SIGTERM}: the primary hands over first. */
    void shutdown() {
        coordinator.stop();
        stopServing();
    }

    private void stopServing() {
        if (serverStarted) {
            serverStarted = false;
            server.stop();
            replication.shutdown();
            aof.close();
        }
    }

    @Override
    public void close() {
        if (coordinator.isRunning()) {
            coordinator.abort();
        }
        stopServing();
        stack.stop();
    }
}
