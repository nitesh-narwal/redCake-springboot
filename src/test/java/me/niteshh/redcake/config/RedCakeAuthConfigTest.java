package me.niteshh.redcake.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedCakeAuthConfigTest {

    @Test
    void shouldMatchConfiguredApiKey() {
        RedCakeAuthConfig config = new RedCakeAuthConfig("secret-ä");

        assertTrue(config.isEnabled());
        assertTrue(config.matches("secret-ä"));
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
