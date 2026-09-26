package me.niteshh.redcake.config;

import java.util.Arrays;
import java.nio.charset.StandardCharsets;

public final class RedCakeAuthConfig {

    private final char[] apiKey;
    private final byte[] apiKeyBytes;

    public RedCakeAuthConfig(String apiKey) {
        this.apiKey = apiKey == null
                ? new char[0]
                : apiKey.toCharArray();
        this.apiKeyBytes = new String(this.apiKey)
                .getBytes(StandardCharsets.UTF_8);
    }

    public boolean isEnabled() {
        return apiKey.length > 0;
    }

    public String apiKey() {
        return new String(apiKey);
    }

    public boolean matches(String candidate) {
        if (!isEnabled() || candidate == null) {
            return false;
        }

        char[] supplied = candidate.toCharArray();
        byte[] suppliedBytes = candidate.getBytes(StandardCharsets.UTF_8);
        try {
            return supplied.length == apiKey.length
                    && suppliedBytes.length == apiKeyBytes.length
                    && java.security.MessageDigest.isEqual(
                    apiKeyBytes,
                    suppliedBytes
            );
        } finally {
            Arrays.fill(supplied, '\0');
            Arrays.fill(suppliedBytes, (byte) 0);
        }
    }
}
