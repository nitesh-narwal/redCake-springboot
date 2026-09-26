package me.niteshh.redcake.store;

public record SnapshotEntry(
        String key,
        String value,
        Long expiresAt
) {
}
