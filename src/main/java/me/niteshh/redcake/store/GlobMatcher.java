package me.niteshh.redcake.store;

/**
 * Redis-style glob matcher used by {@code KEYS}.
 *
 * <p>Supported syntax: {@code *} (any run), {@code ?} (one char),
 * {@code [abc]}, {@code [a-z]}, {@code [^abc]} and {@code \x} escaping.
 * Implemented iteratively with single-star backtracking, so a hostile
 * pattern such as {@code *a*a*a*a*b} cannot cause exponential time.
 */
public final class GlobMatcher {

    private GlobMatcher() {
    }

    /** @return {@code true} if {@code text} matches {@code pattern} */
    public static boolean matches(String pattern, String text) {
        int p = 0;
        int t = 0;
        int starPattern = -1;
        int starText = -1;

        while (t < text.length()) {
            if (p < pattern.length()) {
                char pc = pattern.charAt(p);
                if (pc == '*') {
                    starPattern = ++p;
                    starText = t;
                    continue;
                }
                int consumed = matchSingle(pattern, p, text.charAt(t));
                if (consumed > 0) {
                    p += consumed;
                    t++;
                    continue;
                }
            }
            if (starPattern == -1) {
                return false;
            }
            // Backtrack: let the last '*' swallow one more character.
            p = starPattern;
            t = ++starText;
        }

        while (p < pattern.length() && pattern.charAt(p) == '*') {
            p++;
        }
        return p == pattern.length();
    }

    /**
     * Tries to match one pattern atom at {@code p} against {@code c}.
     *
     * @return pattern characters consumed, or 0 if the atom does not match
     */
    private static int matchSingle(String pattern, int p, char c) {
        char pc = pattern.charAt(p);
        switch (pc) {
            case '?':
                return 1;
            case '\\':
                if (p + 1 < pattern.length()) {
                    return pattern.charAt(p + 1) == c ? 2 : 0;
                }
                return c == '\\' ? 1 : 0;
            case '[': {
                int i = p + 1;
                boolean negate = i < pattern.length() && pattern.charAt(i) == '^';
                if (negate) {
                    i++;
                }
                boolean matched = false;
                while (i < pattern.length() && pattern.charAt(i) != ']') {
                    char lo = pattern.charAt(i);
                    if (lo == '\\' && i + 1 < pattern.length()) {
                        lo = pattern.charAt(++i);
                    }
                    if (i + 2 < pattern.length()
                            && pattern.charAt(i + 1) == '-'
                            && pattern.charAt(i + 2) != ']') {
                        char hi = pattern.charAt(i + 2);
                        if (c >= Math.min(lo, hi) && c <= Math.max(lo, hi)) {
                            matched = true;
                        }
                        i += 3;
                    } else {
                        if (lo == c) {
                            matched = true;
                        }
                        i++;
                    }
                }
                if (i >= pattern.length()) {
                    // Unterminated class: treat '[' literally.
                    return c == '[' ? 1 : 0;
                }
                return (matched != negate) ? (i - p + 1) : 0;
            }
            default:
                return pc == c ? 1 : 0;
        }
    }
}
