package me.niteshh.redcake.store;

public record ExpirationEntry (String key,
                               long expiresAt,
                               long version) {
}
