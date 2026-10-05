package me.niteshh.redcake.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobMatcherTest {

    @Test
    void supportsStarQuestionAndClasses() {
        assertTrue(GlobMatcher.matches("*", "anything"));
        assertTrue(GlobMatcher.matches("user:*", "user:42"));
        assertFalse(GlobMatcher.matches("user:*", "admin:42"));
        assertTrue(GlobMatcher.matches("h?llo", "hello"));
        assertFalse(GlobMatcher.matches("h?llo", "hllo"));
        assertTrue(GlobMatcher.matches("h[ae]llo", "hallo"));
        assertFalse(GlobMatcher.matches("h[^e]llo", "hello"));
        assertTrue(GlobMatcher.matches("k[a-c]", "kb"));
        assertTrue(GlobMatcher.matches("a\\*b", "a*b"));
        assertFalse(GlobMatcher.matches("a\\*b", "axb"));
    }

    @Test
    void hostilePatternDoesNotBlowUp() {
        String text = "a".repeat(5_000);
        assertTimeout(java.time.Duration.ofSeconds(2),
                () -> GlobMatcher.matches("*a*a*a*a*a*a*a*b", text));
    }
}
