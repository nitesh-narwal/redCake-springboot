package me.niteshh.redcake.store;

/**
 * A scheduled expiration: "delete {@code key} at {@code expiresAt} if it still
 * has write-stamp {@code version}".
 */
public record ExpirationEntry(String key,
                              long expiresAt,
                              long version) {
}
