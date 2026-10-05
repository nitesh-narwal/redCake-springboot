package me.niteshh.redcake.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedCakeAuthConfigTest {

    @Test
    void shouldMatchConfiguredApiKey() {
        RedCakeAuthConfig config = new RedCakeAuthConfig("secret-ä");

        assertTrue(config.isEnabled());
        // Clients send bytes; the parser turns them into a byte-string (Latin-1 view of UTF-8).
        String onTheWire = new String("secret-ä".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(config.matches(onTheWire));
        assertFalse(config.matches("secret-ä")); // a decoded Unicode string is not the wire form
        assertFalse(config.matches("secret-a"));
        assertFalse(config.matches(null));
    }

    @Test
    void shouldRemainDisabledWhenNoApiKeyIsConfigured() {
        RedCakeAuthConfig config = new RedCakeAuthConfig(null);

        assertFalse(config.isEnabled());
        assertFalse(config.matches("anything"));
    }
}
