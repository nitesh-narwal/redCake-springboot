package me.niteshh.redcake.store;

/**
 * Optional condition of {@code EXPIRE}/{@code PEXPIRE}/{@code EXPIREAT}
 * (Redis 7 flags). The new deadline is only applied when the condition holds.
 * A key without TTL counts as "infinite TTL" for {@link #GT}/{@link #LT}.
 */
public enum ExpireCondition {
    /** Always apply. */
    NONE,
    /** Only if the key currently has no TTL. */
    NX,
    /** Only if the key currently has a TTL. */
    XX,
    /** Only if the new deadline is later than the current one. */
    GT,
    /** Only if the new deadline is earlier than the current one. */
    LT
}
