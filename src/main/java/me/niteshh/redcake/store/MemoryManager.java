package me.niteshh.redcake.store;

import me.niteshh.redcake.config.MemoryConfig;
import me.niteshh.redcake.stats.ServerStats;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Enforces {@code maxmemory}. Called by the write path, <b>under the primary
 * write lock</b>, right before a write command runs:
 *
 * <ol>
 *   <li>If the estimate is over the limit, evict keys according to the policy
 *       until it is under it (or nothing evictable is left).</li>
 *   <li>If it is still over and the command would grow memory, refuse it
 *       ({@code -OOM}). Commands that only delete are always allowed so the
 *       operator can recover.</li>
 * </ol>
 *
 * Evicted keys are returned so the caller can write an explicit {@code DEL}
 * for each to the replication stream and the AOF - replicas must evict the
 * same keys, not choose their own.
 */
@Component
public class MemoryManager {

    /**
     * Result of {@link #makeRoom}.
     *
     * @param allowed {@code false} if the command must be refused with OOM
     * @param evicted keys that were deleted to make room (to be propagated as DELs)
     */
    public record Outcome(boolean allowed, List<String> evicted) {
    }

    private static final Outcome ALLOWED = new Outcome(true, List.of());

    private final KeyValueStore store;
    private final MemoryConfig config;
    private final ServerStats stats;

    public MemoryManager(KeyValueStore store, MemoryConfig config, ServerStats stats) {
        this.store = store;
        this.config = config;
        this.stats = stats;
    }

    /**
     * @param growing whether the command about to run can increase memory use
     * @return whether it may run and which keys were evicted
     */
    public Outcome makeRoom(boolean growing) {
        long limit = config.getMaxBytes();
        if (limit == 0 || store.usedMemory() <= limit) {
            return ALLOWED;
        }

        List<String> evicted = new ArrayList<>();
        MemoryConfig.Policy policy = config.getPolicy();
        while (store.usedMemory() > limit && policy != MemoryConfig.Policy.NOEVICTION) {
            String victim = policy == MemoryConfig.Policy.VOLATILE_TTL
                    ? store.volatileCandidate()
                    : store.anyKey();
            if (victim == null || !store.delete(victim)) {
                if (victim == null) {
                    break; // nothing left that the policy may evict
                }
                continue; // already gone (expired meanwhile); pick another
            }
            evicted.add(victim);
            stats.keyEvicted();
        }

        boolean overLimit = store.usedMemory() > limit;
        return new Outcome(!(growing && overLimit), evicted);
    }
}
