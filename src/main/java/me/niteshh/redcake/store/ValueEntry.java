package me.niteshh.redcake.store;

/**
 * Immutable stored value wrapper.
 *
 * <p>The entry is never modified; every mutation installs a new entry. That
 * keeps lock-free reads of the <em>wrapper</em> safe and lets {@code version}
 * act as a fingerprint: an expiration event only applies if the key still has
 * the exact version it was scheduled for, and {@code WATCH} detects changes by
 * comparing versions. (Collections inside are mutated in place, but only while
 * the key's atomic section is held.)
 *
 * @param value     a {@link String} or a collection, see {@link ValueType}
 * @param expiresAt absolute expiry in epoch milliseconds, or {@code null}
 * @param version   unique, increasing write stamp
 * @param bytes     estimated memory footprint, used for {@code maxmemory}
 */
public record ValueEntry(Object value, Long expiresAt, long version, long bytes) {

    /** @return the {@link ValueType} of {@link #value()} */
    public ValueType type() {
        return ValueType.of(value);
    }

    /**
     * @return the string value
     * @throws WrongTypeException if this entry holds a collection
     */
    public String asString() {
        if (value instanceof String string) {
            return string;
        }
        throw new WrongTypeException();
    }

    /** @return {@code true} if the TTL has elapsed at the current wall-clock time */
    public boolean isExpired() {
        return isExpiredAt(System.currentTimeMillis());
    }

    /** @return {@code true} if the TTL has elapsed at {@code nowMillis} */
    public boolean isExpiredAt(long nowMillis) {
        return expiresAt != null && nowMillis >= expiresAt;
    }
}
