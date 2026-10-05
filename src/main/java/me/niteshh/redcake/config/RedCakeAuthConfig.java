package me.niteshh.redcake.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Shared API-key authentication settings.
 *
 * <p>Only a SHA-256 digest of the key is used for comparison. Hashing both
 * sides first makes the compared byte arrays equal length, so the comparison
 * time leaks neither the key content nor its <em>length</em> (the previous
 * implementation short-circuited on length). {@code apiKey()} is still
 * available because a replica must send the raw key to its primary.
 */
public final class RedCakeAuthConfig {

    private final char[] apiKey;
    private final byte[] apiKeyDigest;

    /**
     * @param apiKey the key as typed by the operator (any Unicode text)
     */
    public RedCakeAuthConfig(String apiKey) {
        // The wire format is the byte-string form (see RespParser), so keep the key in
        // that form: its UTF-8 bytes, one char per byte.
        String byteString = apiKey == null
                ? ""
                : new String(apiKey.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
        this.apiKey = byteString.toCharArray();
        this.apiKeyDigest = this.apiKey.length == 0 ? new byte[0] : sha256(byteString);
    }

    /** @return {@code true} if a non-empty key is configured (auth required) */
    public boolean isEnabled() {
        return apiKey.length > 0;
    }

    /**
     * @return the configured key in wire (byte-string) form, ready to be sent
     * by a replica to its primary
     */
    public String apiKey() {
        return new String(apiKey);
    }

    /**
     * Constant-time check of a client-supplied key (byte-string form, as
     * parsed from the wire). Always false when auth is disabled.
     */
    public boolean matches(String candidate) {
        if (!isEnabled() || candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(apiKeyDigest, sha256(candidate));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.ISO_8859_1));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM spec", e);
        }
    }
}
