package me.niteshh.redcake.persistence;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import me.niteshh.redcake.replication.ReplicationManager;
import me.niteshh.redcake.stats.ServerStats;
import me.niteshh.redcake.store.KeyValueStore;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * Turns every key expiry on a primary into an explicit {@code DEL} in the
 * AOF and the replication stream.
 *
 * <p>Without it each node would drop the key by its own clock: replicas
 * could briefly serve a key the primary already removed, and an AOF replay
 * after a long downtime would depend on the replay time. With it, the
 * primary is the single authority on <em>when</em> a key disappears
 * ("the primary decides, everyone else follows the log").
 *
 * <p>The store calls {@link #onExpired} outside its own locks, so taking the
 * write lock here cannot deadlock with a writer that holds it.
 */
@Slf4j
@Component
public class ExpiryPropagator {

    private final KeyValueStore store;
    private final ReplicationManager replicationManager;
    private final AppendOnlyLog appendOnlyLog;
    private final ServerStats stats;

    public ExpiryPropagator(
            KeyValueStore store,
            ReplicationManager replicationManager,
            AppendOnlyLog appendOnlyLog,
            ServerStats stats
    ) {
        this.store = store;
        this.replicationManager = replicationManager;
        this.appendOnlyLog = appendOnlyLog;
        this.stats = stats;
    }

    /** Registers this component as the store's expiry listener. */
    @PostConstruct
    public void register() {
        store.setExpiryListener(this::onExpired);
    }

    /** Records the expiry; only a primary writes DELs (a replica waits for its primary's). */
    void onExpired(String key) {
        stats.keyExpired();
        if (!replicationManager.isPrimary()) {
            return;
        }
        List<String> del = List.of("DEL", key);
        replicationManager.lockPrimaryWrites();
        try {
            appendOnlyLog.append(del);
            replicationManager.replicate(del);
        } catch (IOException e) {
            log.error("Could not log expiry of a key: {}", e.getMessage());
        } finally {
            replicationManager.unlockPrimaryWrites();
        }
    }
}
