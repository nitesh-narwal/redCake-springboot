package me.niteshh.redcake.server;

import me.niteshh.redcake.pubsub.Subscriber;
import me.niteshh.redcake.replication.primary.ReplicaConnection;
import me.niteshh.redcake.resp.RespValue;
import me.niteshh.redcake.security.Role;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything RedCake remembers about one client connection: who it is
 * (user, role), what it is doing (transaction, subscriptions) and basic
 * bookkeeping for {@code CLIENT LIST}.
 *
 * <p>Fields marked non-volatile are touched only by the connection's own
 * thread. The few read by other threads ({@code CLIENT LIST}, Pub/Sub
 * delivery) are volatile or immutable.
 */
final class Session implements Subscriber {

    final long id;
    final Socket socket;
    final ConnectionOutput out;
    final String address;
    final String ip;
    final long connectedAtMillis = System.currentTimeMillis();

    volatile long lastActiveMillis = connectedAtMillis;
    volatile String lastCommand = "NULL";
    volatile String name = "";

    // ---- authentication
    boolean authenticated;
    String user = "default";
    Role role = Role.ADMIN;
    int failedLogins;

    // ---- control flow
    boolean closeRequested;

    // ---- replication link (set once this connection became a replica's stream)
    int replicaPort;
    ReplicaConnection attachedReplica;

    // ---- pub/sub
    final Set<String> channels = new LinkedHashSet<>();
    final Set<String> patterns = new LinkedHashSet<>();

    // ---- transactions
    boolean inMulti;
    boolean multiDirty;
    final List<List<String>> queued = new ArrayList<>();
    final Map<String, Long> watched = new HashMap<>();

    Session(long id, Socket socket, ConnectionOutput out, String address, String ip) {
        this.id = id;
        this.socket = socket;
        this.out = out;
        this.address = address;
        this.ip = ip;
    }

    /** @return {@code true} while the connection holds any subscription */
    boolean isSubscribed() {
        return !channels.isEmpty() || !patterns.isEmpty();
    }

    /** @return number of channel plus pattern subscriptions (the count shown in replies) */
    int subscriptionCount() {
        return channels.size() + patterns.size();
    }

    /** Clears MULTI state after EXEC or DISCARD. */
    void resetTransaction() {
        inMulti = false;
        multiDirty = false;
        queued.clear();
        watched.clear();
    }

    @Override
    public void push(RespValue message) throws IOException {
        out.push(message);
    }

    /** Closes the socket; the connection thread then ends on its own. */
    void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // already closed
        }
    }
}
