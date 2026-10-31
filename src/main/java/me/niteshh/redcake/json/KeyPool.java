package me.niteshh.redcake.json;

import java.nio.charset.StandardCharsets;

/**
 * Shares the strings of object keys between documents.
 *
 * <p>Records of one kind repeat the same few key names ({@code "id"}, {@code "text"}, {@code "embedding"},
 * {@code "status"}, ...). Without sharing, a million records hold a million copies of each name - tens of bytes per
 * key, a visible part of a small record. The pool is a fixed, direct-mapped table: a miss simply replaces the
 * slot, so it stays a few tens of kilobytes however many distinct keys pass through, and it needs no lock. Slots are
 * written without synchronization on purpose: a {@link String} is immutable, a reader that sees a stale or a
 * different string compares its content before using it, and a lost update costs only a duplicate string.
 */
final class KeyPool {

    private static final int SLOTS = 4096; // power of two
    /** Longer keys are rarely shared and not worth a slot. */
    private static final int MAX_LENGTH = 32;
    private static final String[] POOL = new String[SLOTS];

    private KeyPool() {
    }

    /** @return the shared string for the {@code len} bytes of {@code b} from {@code off} (ISO-8859-1 chars) */
    static String intern(byte[] b, int off, int len) {
        if (len > MAX_LENGTH) {
            return new String(b, off, len, StandardCharsets.ISO_8859_1);
        }
        int hash = 0;
        for (int i = 0; i < len; i++) {
            hash = 31 * hash + (b[off + i] & 0xFF); // the hash String.hashCode() gives the resulting string
        }
        int slot = (hash ^ (hash >>> 12)) & (SLOTS - 1);
        String cached = POOL[slot];
        if (cached != null && cached.length() == len && sameBytes(cached, b, off, len)) {
            return cached;
        }
        String fresh = new String(b, off, len, StandardCharsets.ISO_8859_1);
        POOL[slot] = fresh;
        return fresh;
    }

    /** @return the shared string equal to {@code key} (for names that arrive as a path, not as JSON text) */
    static String intern(String key) {
        if (key.length() > MAX_LENGTH) {
            return key;
        }
        int hash = key.hashCode();
        int slot = (hash ^ (hash >>> 12)) & (SLOTS - 1);
        String cached = POOL[slot];
        if (cached != null && cached.equals(key)) {
            return cached;
        }
        POOL[slot] = key;
        return key;
    }

    private static boolean sameBytes(String s, byte[] b, int off, int len) {
        for (int i = 0; i < len; i++) {
            if (s.charAt(i) != (b[off + i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }
}
