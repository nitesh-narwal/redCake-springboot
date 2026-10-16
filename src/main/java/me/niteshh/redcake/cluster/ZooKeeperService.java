package me.niteshh.redcake.cluster;

import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.ACLProvider;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The only class that talks to ZooKeeper (through Apache Curator). It offers the handful of primitives the
 * {@link FailoverCoordinator} needs and hides ZooKeeper's quirks: one-shot watches, session replacement,
 * partially created paths.
 *
 * <h3>Layout under {@code /redcake/<cluster-name>}</h3>
 * <pre>
 * /epoch      persistent  highest leader epoch ever granted (compare-and-set: the fencing token)
 * /leader     EPHEMERAL   the current primary; vanishes with its session
 * /nodes/<id> EPHEMERAL   one record per live node (offset, priority, data epoch ...)
 * /failover   EPHEMERAL   "hand over to X" hint written by a primary that steps down on purpose
 * </pre>
 *
 * <h3>Why a leader election cannot produce two leaders</h3>
 * {@link #claimLeadership} is one ZooKeeper <em>multi</em> transaction: "bump {@code /epoch} from version v
 * <b>and</b> create {@code /leader}". ZooKeeper applies it atomically and in a single global order, so of any
 * number of simultaneous candidates exactly one succeeds, and every epoch number is granted at most once.
 *
 * <p>All methods throw ZooKeeper/Curator exceptions on connection problems; the coordinator treats those as
 * "try again on the next tick".
 */
@Slf4j
@Component
public class ZooKeeperService {

    /** Connection events, simplified from Curator's {@code ConnectionState}. */
    public enum ConnectionEvent {
        /** First connection of the client. */
        CONNECTED,
        /** The connection dropped; the session may still be alive. Everything is uncertain from now on. */
        SUSPENDED,
        /** The connection is back and the <em>same</em> session survived. */
        RECONNECTED,
        /** The session expired: all ephemeral znodes of this node are gone. */
        LOST
    }

    /**
     * @param ownerSession ZooKeeper session id that owns the (ephemeral) znode
     * @param version      znode data version, for a guarded delete
     */
    public record LeaderView(LeaderRecord record, long ownerSession, int version) {
    }

    public record EpochView(long value, int version) {
    }

    /** Parent of all clusters' namespaces on one ZooKeeper (Curator creates it on demand). */
    static final String SHARED_ROOT = "/redcake";
    static final String NODES = "/nodes";
    static final String LEADER = "/leader";
    static final String EPOCH = "/epoch";
    static final String FAILOVER = "/failover";

    private final ClusterConfig config;
    private volatile CuratorFramework client;
    private volatile Runnable changeListener = () -> { };
    private volatile boolean layoutReady;

    /** One shared instance: ZooKeeper registers the same watcher object on a path only once. */
    private final Watcher watcher = event -> {
        if (event.getType() != Watcher.Event.EventType.None) {
            changeListener.run();
        }
    };

    public ZooKeeperService(ClusterConfig config) {
        this.config = config;
    }

    // ------------------------------------------------------------ lifecycle

    /**
     * Creates the client and starts connecting in the background.
     *
     * @param connectionListener called (on a Curator thread - do not block) when the connection state changes
     * @param changeListener     called when a watched znode changes; re-read the state in response
     */
    public synchronized void start(Consumer<ConnectionEvent> connectionListener, Runnable changeListener) {
        if (client != null) {
            return;
        }
        this.changeListener = changeListener;

        CuratorFrameworkFactory.Builder builder = CuratorFrameworkFactory.builder()
                .connectString(config.getConnectString())
                .sessionTimeoutMs(config.getSessionTimeoutMs())
                .connectionTimeoutMs(config.getConnectionTimeoutMs())
                // Operations fail fast; the coordinator retries on its next tick.
                .retryPolicy(new ExponentialBackoffRetry(100, 2, 1_000))
                .namespace("redcake/" + config.getClusterName());
        if (config.getZkAuth() != null) {
            builder.authorization("digest", config.getZkAuth().getBytes(StandardCharsets.UTF_8));
            // Everything below /redcake/<cluster> belongs to the credentials of this cluster. The shared parent
            // /redcake must stay creatable for other clusters on the same ZooKeeper, which have other credentials.
            builder.aclProvider(new ACLProvider() {
                @Override
                public List<ACL> getDefaultAcl() {
                    return ZooDefs.Ids.CREATOR_ALL_ACL;
                }

                @Override
                public List<ACL> getAclForPath(String path) {
                    return SHARED_ROOT.equals(path) ? ZooDefs.Ids.OPEN_ACL_UNSAFE : ZooDefs.Ids.CREATOR_ALL_ACL;
                }
            });
        }

        CuratorFramework created = builder.build();
        created.getConnectionStateListenable().addListener((curator, state) -> {
            ConnectionEvent event = switch (state) {
                case CONNECTED -> ConnectionEvent.CONNECTED;
                case RECONNECTED -> ConnectionEvent.RECONNECTED;
                case LOST -> ConnectionEvent.LOST;
                default -> ConnectionEvent.SUSPENDED; // SUSPENDED and READ_ONLY: no writes possible
            };
            log.info("ZooKeeper connection: {}", event);
            connectionListener.accept(event);
        });
        created.start();
        client = created;
    }

    /** Closes the session: ZooKeeper removes this node's ephemeral znodes at once. */
    public synchronized void close() {
        CuratorFramework closing = client;
        client = null;
        layoutReady = false;
        if (closing != null) {
            closing.close();
        }
    }

    public boolean isConnected() {
        CuratorFramework current = client;
        return current != null && current.getZookeeperClient().isConnected();
    }

    public long sessionId() throws Exception {
        return client.getZookeeperClient().getZooKeeper().getSessionId();
    }

    /** The session timeout ZooKeeper granted; it clamps the requested one into its own limits. */
    public int negotiatedSessionTimeoutMs() throws Exception {
        return client.getZookeeperClient().getZooKeeper().getSessionTimeout();
    }

    // --------------------------------------------------------------- layout

    /** Creates the persistent skeleton (idempotent, safe against concurrent creation by other nodes). */
    public void ensureLayout() throws Exception {
        if (layoutReady) {
            return;
        }
        createIfMissing(NODES, new byte[0]);
        createIfMissing(EPOCH, "0\n".getBytes(StandardCharsets.UTF_8));
        layoutReady = true;
    }

    private void createIfMissing(String path, byte[] data) throws Exception {
        try {
            client.create().creatingParentContainersIfNeeded().withMode(CreateMode.PERSISTENT).forPath(path, data);
        } catch (KeeperException.NodeExistsException alreadyThere) {
            // another node created it first
        }
    }

    // ---------------------------------------------------------------- epoch

    public EpochView readEpoch() throws Exception {
        Stat stat = new Stat();
        byte[] data;
        try {
            data = client.getData().storingStatIn(stat).forPath(EPOCH);
        } catch (KeeperException.NoNodeException deleted) {
            layoutReady = false; // an operator removed it; rebuild
            ensureLayout();
            data = client.getData().storingStatIn(stat).forPath(EPOCH);
        }
        try {
            return new EpochView(Long.parseLong(new String(data, StandardCharsets.UTF_8).strip()), stat.getVersion());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("ZooKeeper node " + EPOCH + " does not contain a number", e);
        }
    }

    // --------------------------------------------------------------- leader

    /**
     * Reads the leader znode.
     *
     * @param watch also arm a one-shot watch that fires when the znode is created, changed or deleted
     */
    public Optional<LeaderView> readLeader(boolean watch) throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            Stat stat = new Stat();
            try {
                byte[] data = watch
                        ? client.getData().storingStatIn(stat).usingWatcher(watcher).forPath(LEADER)
                        : client.getData().storingStatIn(stat).forPath(LEADER);
                return Optional.of(new LeaderView(LeaderRecord.parse(data), stat.getEphemeralOwner(), stat.getVersion()));
            } catch (KeeperException.NoNodeException absent) {
                if (!watch) {
                    return Optional.empty();
                }
                // Watch for its creation; if it appeared in the meantime, read again.
                if (client.checkExists().usingWatcher(watcher).forPath(LEADER) == null) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Atomically takes the leadership: bumps {@code /epoch} (only if it still has the version we read) and
     * creates the ephemeral {@code /leader}. Both happen or neither.
     *
     * @return {@code true} if this node is now the leader; {@code false} if somebody else was faster
     */
    public boolean claimLeadership(LeaderRecord record, EpochView expected) throws Exception {
        try {
            client.transaction().forOperations(
                    client.transactionOp().setData().withVersion(expected.version())
                            .forPath(EPOCH, (record.epoch() + "\n").getBytes(StandardCharsets.UTF_8)),
                    client.transactionOp().create().withMode(CreateMode.EPHEMERAL)
                            .forPath(LEADER, record.toBytes()));
            return true;
        } catch (KeeperException.NodeExistsException | KeeperException.BadVersionException lost) {
            return false;
        }
    }

    /**
     * Deletes the leader znode, but only the exact incarnation we saw ({@code version}); never a successor's.
     *
     * @return {@code true} if it was deleted
     */
    public boolean releaseLeader(int version) throws Exception {
        try {
            client.delete().withVersion(version).forPath(LEADER);
            return true;
        } catch (KeeperException.NoNodeException | KeeperException.BadVersionException gone) {
            return false;
        }
    }

    // -------------------------------------------------------------- members

    /** Creates or refreshes this node's ephemeral record, taking over a leftover of its previous run. */
    public void registerMember(NodeRecord record) throws Exception {
        String path = NODES + "/" + record.nodeId();
        byte[] data = record.toBytes();
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                client.create().creatingParentContainersIfNeeded().withMode(CreateMode.EPHEMERAL).forPath(path, data);
                return;
            } catch (KeeperException.NodeExistsException exists) {
                Stat stat = client.checkExists().forPath(path);
                if (stat == null) {
                    continue; // vanished again, create it
                }
                if (stat.getEphemeralOwner() == sessionId()) {
                    client.setData().forPath(path, data);
                    return;
                }
                // Left behind by an earlier run of this node whose session has not expired yet.
                log.info("Replacing the member record of this node's previous run");
                try {
                    client.transaction().forOperations(
                            client.transactionOp().delete().withVersion(stat.getVersion()).forPath(path),
                            client.transactionOp().create().withMode(CreateMode.EPHEMERAL).forPath(path, data));
                    return;
                } catch (KeeperException.NoNodeException | KeeperException.BadVersionException | KeeperException.NodeExistsException race) {
                    // changed under us; look again
                }
            }
        }
        throw new IllegalStateException("Could not register node " + record.nodeId() + " in ZooKeeper");
    }

    /** Publishes new content for this node's record (re-creating it if it vanished). */
    public void updateMember(NodeRecord record) throws Exception {
        try {
            client.setData().forPath(NODES + "/" + record.nodeId(), record.toBytes());
        } catch (KeeperException.NoNodeException gone) {
            registerMember(record);
        }
    }

    /** @return every live node's record; arms a watch for nodes joining or leaving */
    public List<NodeRecord> readMembers() throws Exception {
        List<String> children = client.getChildren().usingWatcher(watcher).forPath(NODES);
        List<NodeRecord> members = new ArrayList<>(children.size());
        for (String child : children) {
            try {
                members.add(NodeRecord.parse(client.getData().forPath(NODES + "/" + child)));
            } catch (KeeperException.NoNodeException left) {
                // left in the meantime
            } catch (IllegalArgumentException unreadable) {
                log.warn("Ignoring unreadable node record '{}': {}", child, unreadable.getMessage());
            }
        }
        return members;
    }

    // --------------------------------------------------------- failover hint

    /** Publishes a hand-over hint (ephemeral: it dies with the writer's session). */
    public void writeFailoverHint(FailoverHint hint) throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                client.create().withMode(CreateMode.EPHEMERAL).forPath(FAILOVER, hint.toBytes());
                return;
            } catch (KeeperException.NodeExistsException exists) {
                try {
                    client.delete().forPath(FAILOVER);
                } catch (KeeperException.NoNodeException gone) {
                    // somebody else removed it
                }
            }
        }
        throw new IllegalStateException("Could not publish the failover hint");
    }

    public Optional<FailoverHint> readFailoverHint() throws Exception {
        try {
            return Optional.of(FailoverHint.parse(client.getData().forPath(FAILOVER)));
        } catch (KeeperException.NoNodeException absent) {
            return Optional.empty();
        } catch (IllegalArgumentException unreadable) {
            return Optional.empty();
        }
    }

    public void clearFailoverHint() throws Exception {
        try {
            client.delete().forPath(FAILOVER);
        } catch (KeeperException.NoNodeException gone) {
            // nothing to clear
        }
    }
}
