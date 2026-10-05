package me.niteshh.redcake.support;

import me.niteshh.redcake.command.CommandHandler;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.command.commands.reads.*;
import me.niteshh.redcake.config.*;
import me.niteshh.redcake.persistence.AppendOnlyLog;
import me.niteshh.redcake.persistence.ExpiryPropagator;
import me.niteshh.redcake.pubsub.PubSubBroker;
import me.niteshh.redcake.replication.ReplicationConfig;
import me.niteshh.redcake.replication.ReplicationManager;
import me.niteshh.redcake.replication.ReplicationRole;
import me.niteshh.redcake.replication.primary.ReplicaManager;
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

import java.net.ServerSocket;
import java.util.List;

import static org.mockito.Mockito.mock;

/**
 * A complete in-JVM RedCake node on a free port, wired like the Spring
 * context does it (all commands, stats, ACL, memory manager, Pub/Sub, ...),
 * for black-box tests through a real socket. Configure with the builder-style
 * {@code with...} methods <em>before</em> calling {@link #start()}.
 */
public final class TestServer implements AutoCloseable {

    public final TestSupport.Stack stack = TestSupport.newStack();
    public final ServerStats serverStats = new ServerStats();
    public final ReplicationStats replicationStats = new ReplicationStats();
    public final CommandStats commandStats = new CommandStats();
    public final SlowLog slowLog = new SlowLog();
    public final PubSubBroker broker = new PubSubBroker();
    public final ClientRegistry clientRegistry = new ClientRegistry();
    public final ReplicaManager replicaManager = new ReplicaManager();
    public final MemoryConfig memoryConfig = MemoryConfig.unlimited();
    public int port;

    private RedCakeAuthConfig auth = new RedCakeAuthConfig(null);
    private AclRegistry acl = AclRegistry.empty();
    private TlsConfig tls = TlsConfig.disabled();
    private ReplicationRole role = ReplicationRole.PRIMARY;
    private PersistenceConfig persistence = PersistenceConfig.disabled();
    private int idleTimeoutSeconds;

    private ReplicationConfig replicationConfig;
    private ReplicationManager replication;
    private AppendOnlyLog aof;
    private RedCakeServer server;

    public TestServer withApiKey(String key) {
        this.auth = new RedCakeAuthConfig(key);
        return this;
    }

    public TestServer withAcl(AclRegistry registry) {
        this.acl = registry;
        return this;
    }

    public TestServer withTls(TlsConfig tlsConfig) {
        this.tls = tlsConfig;
        return this;
    }

    public TestServer withRole(ReplicationRole replicationRole) {
        this.role = replicationRole;
        return this;
    }

    public TestServer withAof(PersistenceConfig config) {
        this.persistence = config;
        return this;
    }

    public TestServer withIdleTimeout(int seconds) {
        this.idleTimeoutSeconds = seconds;
        return this;
    }

    public ReplicationManager replication() {
        return replication;
    }

    public AppendOnlyLog aof() {
        return aof;
    }

    /** Wires everything and starts listening. */
    public TestServer start() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }

        replicationConfig = new ReplicationConfig(role,
                role == ReplicationRole.REPLICA ? "127.0.0.1" : null, 6379);
        replication = new ReplicationManager(
                replicationConfig, replicaManager, mock(ReplicaSyncManager.class),
                stack.store(), replicationStats);

        List<RedCakeCommand> commands = TestSupport.baseCommands(stack.store());
        commands.add(new InfoCommand(stack.store(), replicationConfig, serverStats, replicationStats,
                replicaManager, persistence, memoryConfig, commandStats));
        commands.add(new PublishCommand(broker));
        commands.add(new PubSubCommand(broker));
        commands.add(new RoleCommand(replicationConfig, replicationStats, replicaManager));
        commands.add(new SlowlogCommand(slowLog));
        commands.add(new ConfigCommand(memoryConfig,
                new RedCakeServerConfig(port, "127.0.0.1", 100, idleTimeoutSeconds, true),
                persistence, replicationConfig, slowLog));
        CommandHandler handler = new CommandHandler(commands, TestSupport.providers(stack.store()));

        aof = new AppendOnlyLog(persistence, handler, stack.store(), replicationConfig);
        aof.open();
        new ExpiryPropagator(stack.store(), replication, aof, serverStats).register();

        ClientHandler clientHandler = new ClientHandler(
                new RespParser(), handler, new RespWriter(), replication, auth, serverStats, aof,
                new AuthFailureTracker(), acl,
                new MemoryManager(stack.store(), memoryConfig, serverStats),
                broker, clientRegistry, slowLog, commandStats);

        server = new RedCakeServer(clientHandler,
                new RedCakeServerConfig(port, "127.0.0.1", 100, idleTimeoutSeconds, true),
                auth, serverStats, tls);
        server.start();
        return this;
    }

    public RespClient client() throws Exception {
        return new RespClient("127.0.0.1", port);
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop();
        }
        if (aof != null) {
            aof.close();
        }
        stack.stop();
    }
}
