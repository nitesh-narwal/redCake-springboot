package me.niteshh.redcake.store;

/**
 * Options of the {@code SET} command, already parsed and validated.
 *
 * @param expiresAt absolute expiry in epoch milliseconds, or {@code null} for none
 * @param keepTtl   {@code KEEPTTL}: keep the TTL of the existing key
 * @param condition {@code NX}/{@code XX} behaviour
 * @param returnOld {@code GET}: report the previous value
 */
public record SetOptions(
        Long expiresAt,
        boolean keepTtl,
        Condition condition,
        boolean returnOld
) {

    /** Write condition of SET. */
    public enum Condition {
        /** Plain SET. */
        ALWAYS,
        /** {@code NX}: only if the key does not exist. */
        ONLY_IF_ABSENT,
        /** {@code XX}: only if the key already exists. */
        ONLY_IF_PRESENT
    }

    private static final SetOptions PLAIN =
            new SetOptions(null, false, Condition.ALWAYS, false);

    /** Plain {@code SET key value}. */
    public static SetOptions plain() {
        return PLAIN;
    }

    /** {@code SET key value PXAT expiresAt}. */
    public static SetOptions withExpiry(long expiresAt) {
        return new SetOptions(expiresAt, false, Condition.ALWAYS, false);
    }
}
