package me.niteshh.redcake.stats;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide runtime counters surfaced by {@code INFO}.
 *
 * <p>Plain atomics (no locks) so incrementing them on the hot path costs a
 * single CAS. Components that observe events (server accept loop, client
 * handler) update it; {@code InfoCommand} only reads it.
 */
@Component
public class ServerStats {

    private final long startMillis = System.currentTimeMillis();
    private final AtomicLong connectedClients = new AtomicLong();
    private final AtomicLong totalConnections = new AtomicLong();
    private final AtomicLong rejectedConnections = new AtomicLong();
    private final AtomicLong totalCommands = new AtomicLong();
    private final AtomicLong failedAuthentications = new AtomicLong();
    private final AtomicLong evictedKeys = new AtomicLong();
    private final AtomicLong expiredKeys = new AtomicLong();

    /** A client connection was accepted and is now being served. */
    public void clientConnected() {
        connectedClients.incrementAndGet();
        totalConnections.incrementAndGet();
    }

    /** A served client connection ended. */
    public void clientDisconnected() {
        connectedClients.decrementAndGet();
    }

    /** A connection was refused (e.g. maxclients reached). */
    public void connectionRejected() {
        rejectedConnections.incrementAndGet();
    }

    /** One command was dispatched. */
    public void commandProcessed() {
        totalCommands.incrementAndGet();
    }

    /** One AUTH attempt failed. */
    public void authenticationFailed() {
        failedAuthentications.incrementAndGet();
    }

    /** A key was evicted because of {@code maxmemory}. */
    public void keyEvicted() {
        evictedKeys.incrementAndGet();
    }

    /** A key was removed because its TTL elapsed. */
    public void keyExpired() {
        expiredKeys.incrementAndGet();
    }

    public long evictedKeys() {
        return evictedKeys.get();
    }

    public long expiredKeys() {
        return expiredKeys.get();
    }

    public long connectedClients() {
        return connectedClients.get();
    }

    public long totalConnections() {
        return totalConnections.get();
    }

    public long rejectedConnections() {
        return rejectedConnections.get();
    }

    public long totalCommands() {
        return totalCommands.get();
    }

    public long failedAuthentications() {
        return failedAuthentications.get();
    }

    /** @return seconds since this object (and therefore the server) was created */
    public long uptimeSeconds() {
        return (System.currentTimeMillis() - startMillis) / 1000;
    }
}
