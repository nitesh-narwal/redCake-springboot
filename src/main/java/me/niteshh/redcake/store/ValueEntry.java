package me.niteshh.redcake.store;

public record ValueEntry(String value, Long expiresAt, long version) {

    public boolean isExpired() {
        if (expiresAt == null) {
            return false;
        }
        return System.currentTimeMillis() >= expiresAt;
    }
}
