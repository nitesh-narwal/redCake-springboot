package me.niteshh.redcake.store;

import java.util.Collection;
import java.util.Map;

/**
 * Cheap, deterministic memory estimates behind {@code maxmemory}.
 *
 * <p>Exact JVM object sizes are not observable, and walking a large
 * collection on every write would be O(n). So strings are estimated by length
 * and collections by element count times a typical per-element cost. The
 * numbers are intentionally on the generous side: the goal is a guard that
 * triggers <em>before</em> the heap is exhausted, not an accounting system.
 */
public final class MemoryEstimator {

    /** Map node + entry object + key bytes overhead per key. */
    private static final long KEY_OVERHEAD = 96;
    private static final long STRING_OVERHEAD = 24;
    private static final long HASH_FIELD = 112;
    private static final long LIST_ELEMENT = 48;
    private static final long SET_MEMBER = 72;
    private static final long ZSET_MEMBER = 144;

    private MemoryEstimator() {
    }

    /** @return estimated bytes used by {@code key} holding {@code value} */
    public static long sizeOf(String key, Object value) {
        long size = KEY_OVERHEAD + key.length();
        return size + switch (value) {
            case String string -> STRING_OVERHEAD + string.length();
            case Map<?, ?> map -> 64 + map.size() * HASH_FIELD;
            case ZSet zset -> 64 + zset.size() * ZSET_MEMBER;
            case Collection<?> collection -> 48 + collection.size()
                    * (value instanceof java.util.Set ? SET_MEMBER : LIST_ELEMENT);
            default -> 0;
        };
    }

    /** @return {@code true} if a collection value has no elements (such keys are deleted) */
    public static boolean isEmpty(Object value) {
        return switch (value) {
            case Map<?, ?> map -> map.isEmpty();
            case ZSet zset -> zset.size() == 0;
            case Collection<?> collection -> collection.isEmpty();
            default -> false;
        };
    }
}
