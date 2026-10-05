package me.niteshh.redcake.config;

import java.util.Locale;

/**
 * Memory limit and eviction policy ({@code --maxmemory}, {@code --maxmemory-policy};
 * both changeable at runtime with {@code CONFIG SET}).
 *
 * <p>Fields are volatile because CONFIG SET runs on a client thread while the
 * write path reads them on others.
 */
public final class MemoryConfig {

    /** What to do when the estimated memory use exceeds the limit. */
    public enum Policy {
        /** Refuse commands that would grow memory (reply {@code -OOM}). Default. */
        NOEVICTION("noeviction"),
        /** Delete arbitrary keys until under the limit. */
        ALLKEYS_RANDOM("allkeys-random"),
        /** Delete keys that have a TTL, soonest-expiring first. */
        VOLATILE_TTL("volatile-ttl");

        private final String configName;

        Policy(String configName) {
            this.configName = configName;
        }

        public String configName() {
            return configName;
        }

        /** @throws IllegalArgumentException for an unknown name */
        public static Policy parse(String name) {
            for (Policy policy : values()) {
                if (policy.configName.equals(name.toLowerCase(Locale.ROOT))) {
                    return policy;
                }
            }
            throw new IllegalArgumentException(
                    "maxmemory-policy must be one of: noeviction, allkeys-random, volatile-ttl");
        }
    }

    private volatile long maxBytes;
    private volatile Policy policy;

    public MemoryConfig(long maxBytes, Policy policy) {
        setMaxBytes(maxBytes);
        this.policy = policy == null ? Policy.NOEVICTION : policy;
    }

    /** @return a configuration without any limit */
    public static MemoryConfig unlimited() {
        return new MemoryConfig(0, Policy.NOEVICTION);
    }

    /** @return the limit in bytes, 0 meaning unlimited */
    public long getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(long maxBytes) {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxmemory must not be negative");
        }
        this.maxBytes = maxBytes;
    }

    public Policy getPolicy() {
        return policy;
    }

    public void setPolicy(Policy policy) {
        this.policy = policy;
    }

    /**
     * Parses sizes such as {@code 1048576}, {@code 512k}, {@code 100mb}, {@code 2g}.
     *
     * @throws IllegalArgumentException if the text is not a valid size
     */
    public static long parseSize(String text) {
        String value = text.trim().toLowerCase(Locale.ROOT);
        long multiplier = 1;
        for (String[] unit : new String[][]{
                {"kb", "1024"}, {"mb", "1048576"}, {"gb", "1073741824"},
                {"k", "1024"}, {"m", "1048576"}, {"g", "1073741824"}}) {
            if (value.endsWith(unit[0])) {
                multiplier = Long.parseLong(unit[1]);
                value = value.substring(0, value.length() - unit[0].length());
                break;
            }
        }
        try {
            long size = Math.multiplyExact(Long.parseLong(value.trim()), multiplier);
            if (size < 0) {
                throw new IllegalArgumentException("Invalid size: " + text);
            }
            return size;
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("Invalid size: " + text);
        }
    }
}
