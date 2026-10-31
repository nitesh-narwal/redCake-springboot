package me.niteshh.redcake.json;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A strict JSON parser (RFC 8259) for the byte strings RedCake uses: the text is the UTF-8 the client sent, one
 * char per byte.
 *
 * <ul>
 *   <li><b>Strict.</b> No comments, no trailing commas, no leading zeros, no {@code NaN}; control characters must be
 *       escaped. Invalid UTF-8 and lone surrogates ({@code "\ud800"}) are errors, so every stored document can be
 *       read by any JSON library. Duplicate member names: the last value wins.</li>
 *   <li><b>Bounded.</b> Nesting is limited to {@link Json#MAX_DEPTH}, so a hostile {@code [[[[...} cannot overflow the
 *       stack; there is no other recursion.</li>
 *   <li><b>Byte-exact.</b> String contents are kept as the bytes they were: an {@code é} escape becomes the two
 *       UTF-8 bytes of the character, raw UTF-8 passes through untouched.</li>
 *   <li><b>Lean.</b> An array of numbers is collected straight into a {@code long[]} or {@code double[]} without
 *       creating a box per number (see {@link JsonArray}); member names go through the {@link KeyPool}; decimals of up
 *       to 15 digits are converted with one exactly-rounded multiplication or division instead of
 *       {@link Double#parseDouble}.</li>
 * </ul>
 *
 * <p>A parser instance is single-use and not thread-safe; {@link #parse} is the entry point.
 */
public final class JsonParser {

    /** A parsed value and how deeply it nests (0 for a scalar). */
    public record Parsed(Object value, int depth) {
    }

    /** Powers of ten that are exactly representable as doubles. */
    private static final double[] POW10 = {
            1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11,
            1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22};
    /** Largest integer a double represents exactly. */
    private static final long MAX_EXACT = 1L << 53;

    private static final int LONG = 1;
    private static final int DOUBLE = 2;

    private final byte[] b;
    private final int end;
    private int pos;
    private int depth;
    private int deepest;
    /** The number {@link #parseNumber} just read: {@link #numLong} or {@link #numDouble}, by its return value. */
    private long numLong;
    private double numDouble;
    /** Scratch space for strings that contain escapes. */
    private byte[] scratch = new byte[64];

    private JsonParser(byte[] bytes) {
        this.b = bytes;
        this.end = bytes.length;
    }

    /**
     * @param text one JSON value, optionally surrounded by whitespace
     * @throws JsonException if it is not valid JSON, with the offset of the problem
     */
    public static Parsed parse(String text) {
        JsonParser parser = new JsonParser(text.getBytes(StandardCharsets.ISO_8859_1));
        parser.skipWhitespace();
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (parser.pos != parser.end) {
            throw parser.error("unexpected characters after the value");
        }
        return new Parsed(value, parser.deepest);
    }

    // ---- values -------------------------------------------------------------------------------------------------

    private Object parseValue() {
        if (pos >= end) {
            throw error("unexpected end of input");
        }
        int c = b[pos];
        switch (c) {
            case '{':
                return parseObject();
            case '[':
                return parseArray();
            case '"':
                return parseString(false);
            case 't':
                expectWord("true");
                return Boolean.TRUE;
            case 'f':
                expectWord("false");
                return Boolean.FALSE;
            case 'n':
                expectWord("null");
                return Json.NULL;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return parseNumber() == LONG ? (Object) Long.valueOf(numLong) : (Object) Double.valueOf(numDouble);
                }
                throw error("unexpected character");
        }
    }

    private void expectWord(String word) {
        int n = word.length();
        if (pos + n > end) {
            throw error("unexpected end of input");
        }
        for (int i = 0; i < n; i++) {
            if (b[pos + i] != word.charAt(i)) {
                throw error("invalid literal");
            }
        }
        pos += n;
    }

    private JsonObject parseObject() {
        enter();
        pos++; // {
        JsonObject object = new JsonObject();
        skipWhitespace();
        if (pos < end && b[pos] == '}') {
            pos++;
            depth--;
            return object;
        }
        while (true) {
            skipWhitespace();
            if (pos >= end || b[pos] != '"') {
                throw error("expected a member name");
            }
            String name = parseString(true);
            skipWhitespace();
            if (pos >= end || b[pos] != ':') {
                throw error("expected ':'");
            }
            pos++;
            skipWhitespace();
            object.putUntracked(name, parseValue());
            skipWhitespace();
            if (pos >= end) {
                throw error("unexpected end of input");
            }
            int c = b[pos++];
            if (c == '}') {
                break;
            }
            if (c != ',') {
                pos--;
                throw error("expected ',' or '}'");
            }
        }
        object.trim();
        depth--;
        return object;
    }

    /**
     * Collects the elements into a {@code long[]} while they are all integers, a {@code double[]} while they are all
     * non-integral numbers, and an {@code Object[]} from the first element that breaks the pattern.
     */
    private JsonArray parseArray() {
        enter();
        pos++; // [
        skipWhitespace();
        if (pos < end && b[pos] == ']') {
            pos++;
            depth--;
            return new JsonArray();
        }
        long[] longs = null;
        double[] doubles = null;
        Object[] objects = null;
        int n = 0;
        int kind = 0; // 0 nothing yet, LONG, DOUBLE, or 3 = generic
        while (true) {
            skipWhitespace();
            if (pos >= end) {
                throw error("unexpected end of input");
            }
            int c = b[pos];
            if (c == '-' || (c >= '0' && c <= '9')) {
                int type = parseNumber();
                if (kind == 0) {
                    kind = type;
                    if (type == LONG) {
                        longs = new long[8];
                    } else {
                        doubles = new double[8];
                    }
                }
                if (kind == type) {
                    if (type == LONG) {
                        if (n == longs.length) {
                            longs = Arrays.copyOf(longs, n + (n >> 1) + 8);
                        }
                        longs[n++] = numLong;
                    } else {
                        if (n == doubles.length) {
                            doubles = Arrays.copyOf(doubles, n + (n >> 1) + 8);
                        }
                        doubles[n++] = numDouble;
                    }
                } else {
                    if (kind != 3) { // the first element that is not like the ones before: box what was collected
                        objects = box(kind, longs, doubles, n);
                        kind = 3;
                        longs = null;
                        doubles = null;
                    }
                    objects = ensure(objects, n);
                    objects[n++] = type == LONG ? (Object) Long.valueOf(numLong) : (Object) Double.valueOf(numDouble);
                }
            } else {
                Object value = parseValue();
                if (kind != 3) {
                    objects = box(kind, longs, doubles, n);
                    kind = 3;
                    longs = null;
                    doubles = null;
                }
                objects = ensure(objects, n);
                objects[n++] = value;
            }
            skipWhitespace();
            if (pos >= end) {
                throw error("unexpected end of input");
            }
            c = b[pos++];
            if (c == ']') {
                break;
            }
            if (c != ',') {
                pos--;
                throw error("expected ',' or ']'");
            }
        }
        depth--;
        return switch (kind) {
            case LONG -> JsonArray.ofLongs(Arrays.copyOf(longs, n), n);
            case DOUBLE -> JsonArray.ofDoubles(Arrays.copyOf(doubles, n), n);
            default -> JsonArray.ofGeneric(Arrays.copyOf(objects, n), n);
        };
    }

    /** The elements collected so far as boxed objects (an array of numbers that turns out not to be only numbers). */
    private static Object[] box(int kind, long[] longs, double[] doubles, int n) {
        Object[] boxed = new Object[Math.max(8, n + (n >> 1) + 1)];
        if (kind == LONG) {
            for (int i = 0; i < n; i++) {
                boxed[i] = Long.valueOf(longs[i]);
            }
        } else if (kind == DOUBLE) {
            for (int i = 0; i < n; i++) {
                boxed[i] = Double.valueOf(doubles[i]);
            }
        }
        return boxed;
    }

    private static Object[] ensure(Object[] objects, int n) {
        return n == objects.length ? Arrays.copyOf(objects, n + (n >> 1) + 8) : objects;
    }

    private void enter() {
        if (++depth > Json.MAX_DEPTH) {
            throw error("nesting is deeper than " + Json.MAX_DEPTH + " levels");
        }
        if (depth > deepest) {
            deepest = depth;
        }
    }

    // ---- strings ------------------------------------------------------------------------------------------------

    /** Reads the string that starts at the opening quote under {@code pos}. */
    private String parseString(boolean isName) {
        int start = ++pos;
        boolean high = false;
        for (int i = start; i < end; i++) {
            int c = b[i] & 0xFF;
            if (c == '"') {
                if (high && !validUtf8(b, start, i)) {
                    throw error("invalid UTF-8 in a string", start);
                }
                pos = i + 1;
                return isName ? KeyPool.intern(b, start, i - start)
                        : new String(b, start, i - start, StandardCharsets.ISO_8859_1);
            }
            if (c == '\\') {
                return parseEscapedString(start, i);
            }
            if (c < 0x20) {
                throw error("control character in a string; it must be escaped", i);
            }
            if (c >= 0x80) {
                high = true;
            }
        }
        throw error("unterminated string", start);
    }

    /** The slow path: the string contains an escape, so it is rebuilt in {@link #scratch}. */
    private String parseEscapedString(int start, int firstEscape) {
        int n = firstEscape - start;
        if (scratch.length < n + 16) {
            scratch = new byte[Math.max(n + 16, scratch.length * 2)];
        }
        System.arraycopy(b, start, scratch, 0, n);
        int i = firstEscape;
        while (i < end) {
            int c = b[i] & 0xFF;
            if (n + 4 >= scratch.length) {
                scratch = Arrays.copyOf(scratch, scratch.length * 2);
            }
            if (c == '"') {
                if (!validUtf8(scratch, 0, n)) {
                    throw error("invalid UTF-8 in a string", start);
                }
                pos = i + 1;
                return new String(scratch, 0, n, StandardCharsets.ISO_8859_1);
            }
            if (c < 0x20) {
                throw error("control character in a string; it must be escaped", i);
            }
            if (c != '\\') {
                scratch[n++] = (byte) c;
                i++;
                continue;
            }
            if (++i >= end) {
                break;
            }
            switch (b[i]) {
                case '"', '\\', '/' -> scratch[n++] = b[i++];
                case 'b' -> {
                    scratch[n++] = '\b';
                    i++;
                }
                case 'f' -> {
                    scratch[n++] = '\f';
                    i++;
                }
                case 'n' -> {
                    scratch[n++] = '\n';
                    i++;
                }
                case 'r' -> {
                    scratch[n++] = '\r';
                    i++;
                }
                case 't' -> {
                    scratch[n++] = '\t';
                    i++;
                }
                case 'u' -> {
                    int unit = hex4(i + 1);
                    i += 5;
                    int codePoint = unit;
                    if (unit >= 0xD800 && unit <= 0xDBFF) {
                        if (i + 5 < end && b[i] == '\\' && b[i + 1] == 'u') {
                            int low = hex4(i + 2);
                            if (low >= 0xDC00 && low <= 0xDFFF) {
                                codePoint = 0x10000 + ((unit - 0xD800) << 10) + (low - 0xDC00);
                                i += 6;
                            } else {
                                throw error("unpaired surrogate in a string escape", i);
                            }
                        } else {
                            throw error("unpaired surrogate in a string escape", i);
                        }
                    } else if (unit >= 0xDC00 && unit <= 0xDFFF) {
                        throw error("unpaired surrogate in a string escape", i);
                    }
                    n = putUtf8(codePoint, n);
                }
                default -> throw error("invalid escape", i);
            }
        }
        throw error("unterminated string", start);
    }

    private int hex4(int at) {
        if (at + 4 > end) {
            throw error("incomplete \\u escape", at);
        }
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int c = b[at + i];
            int digit = c >= '0' && c <= '9' ? c - '0'
                    : c >= 'a' && c <= 'f' ? c - 'a' + 10
                    : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
            if (digit < 0) {
                throw error("invalid \\u escape", at + i);
            }
            value = (value << 4) | digit;
        }
        return value;
    }

    private int putUtf8(int cp, int n) {
        if (cp < 0x80) {
            scratch[n++] = (byte) cp;
        } else if (cp < 0x800) {
            scratch[n++] = (byte) (0xC0 | (cp >> 6));
            scratch[n++] = (byte) (0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            scratch[n++] = (byte) (0xE0 | (cp >> 12));
            scratch[n++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
            scratch[n++] = (byte) (0x80 | (cp & 0x3F));
        } else {
            scratch[n++] = (byte) (0xF0 | (cp >> 18));
            scratch[n++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
            scratch[n++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
            scratch[n++] = (byte) (0x80 | (cp & 0x3F));
        }
        return n;
    }

    /**
     * @return whether {@code a[from, to)} is well-formed UTF-8: no overlong forms, no surrogates, nothing above
     * U+10FFFF
     */
    static boolean validUtf8(byte[] a, int from, int to) {
        int i = from;
        while (i < to) {
            int c = a[i++] & 0xFF;
            if (c < 0x80) {
                continue;
            }
            if (c < 0xC2) {
                return false;
            }
            if (c < 0xE0) {
                if (i >= to || (a[i++] & 0xC0) != 0x80) {
                    return false;
                }
            } else if (c < 0xF0) {
                if (i + 1 >= to) {
                    return false;
                }
                int c1 = a[i++] & 0xFF;
                int c2 = a[i++] & 0xFF;
                if ((c1 & 0xC0) != 0x80 || (c2 & 0xC0) != 0x80
                        || (c == 0xE0 && c1 < 0xA0)    // overlong
                        || (c == 0xED && c1 >= 0xA0)) { // a surrogate
                    return false;
                }
            } else if (c < 0xF5) {
                if (i + 2 >= to) {
                    return false;
                }
                int c1 = a[i++] & 0xFF;
                int c2 = a[i++] & 0xFF;
                int c3 = a[i++] & 0xFF;
                if ((c1 & 0xC0) != 0x80 || (c2 & 0xC0) != 0x80 || (c3 & 0xC0) != 0x80
                        || (c == 0xF0 && c1 < 0x90)    // overlong
                        || (c == 0xF4 && c1 >= 0x90)) { // above U+10FFFF
                    return false;
                }
            } else {
                return false;
            }
        }
        return true;
    }

    // ---- numbers ------------------------------------------------------------------------------------------------

    /**
     * Reads the number under {@code pos}.
     *
     * @return {@link #LONG} (the value is in {@link #numLong}) or {@link #DOUBLE} ({@link #numDouble})
     */
    private int parseNumber() {
        int start = pos;
        boolean negative = false;
        if (b[pos] == '-') {
            negative = true;
            pos++;
        }
        if (pos >= end) {
            throw error("incomplete number");
        }
        int c = b[pos];
        long mantissa = 0;
        int digits = 0;      // digits taken into the mantissa
        boolean exact = true; // the mantissa holds every digit read
        if (c == '0') {
            pos++;
            if (pos < end && b[pos] >= '0' && b[pos] <= '9') {
                throw error("a number must not have leading zeros");
            }
        } else if (c >= '1' && c <= '9') {
            while (pos < end && b[pos] >= '0' && b[pos] <= '9') {
                if (digits < 18) {
                    mantissa = mantissa * 10 + (b[pos] - '0');
                    digits++;
                } else {
                    exact = false;
                }
                pos++;
            }
        } else {
            throw error("invalid number");
        }
        int integerEnd = pos;
        int exponent = 0;
        boolean integral = true;
        if (pos < end && b[pos] == '.') {
            integral = false;
            pos++;
            int fractionStart = pos;
            while (pos < end && b[pos] >= '0' && b[pos] <= '9') {
                if (digits < 18) {
                    mantissa = mantissa * 10 + (b[pos] - '0');
                    if (mantissa != 0) {
                        digits++;
                    }
                    exponent--;
                } else {
                    exact = false;
                }
                pos++;
            }
            if (pos == fractionStart) {
                throw error("a digit must follow the decimal point");
            }
        }
        if (pos < end && (b[pos] == 'e' || b[pos] == 'E')) {
            integral = false;
            pos++;
            boolean negativeExponent = false;
            if (pos < end && (b[pos] == '+' || b[pos] == '-')) {
                negativeExponent = b[pos] == '-';
                pos++;
            }
            int exponentStart = pos;
            int e = 0;
            while (pos < end && b[pos] >= '0' && b[pos] <= '9') {
                if (e < 10_000) {
                    e = e * 10 + (b[pos] - '0');
                }
                pos++;
            }
            if (pos == exponentStart) {
                throw error("a digit must follow the exponent");
            }
            exponent += negativeExponent ? -e : e;
        }

        if (integral) {
            if (exact) {
                numLong = negative ? -mantissa : mantissa;
                return LONG;
            }
            String text = new String(b, start, integerEnd - start, StandardCharsets.ISO_8859_1);
            try {
                numLong = Long.parseLong(text);
                return LONG;
            } catch (NumberFormatException tooBig) {
                numDouble = Double.parseDouble(text); // outside 64 bits: a double, like most JSON libraries
                checkFinite(start);
                return DOUBLE;
            }
        }
        if (exact && mantissa <= MAX_EXACT && exponent >= -22 && exponent <= 22) {
            // Both operands are exact doubles, so one IEEE operation rounds correctly: the result is what
            // Double.parseDouble would give.
            double value = exponent < 0 ? mantissa / POW10[-exponent] : mantissa * POW10[exponent];
            numDouble = negative ? -value : value;
            return DOUBLE;
        }
        numDouble = Double.parseDouble(new String(b, start, pos - start, StandardCharsets.ISO_8859_1));
        checkFinite(start);
        return DOUBLE;
    }

    private void checkFinite(int at) {
        if (Double.isInfinite(numDouble)) {
            throw error("number out of range", at);
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private void skipWhitespace() {
        while (pos < end) {
            int c = b[pos];
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                pos++;
            } else {
                return;
            }
        }
    }

    private JsonException error(String message) {
        return error(message, pos);
    }

    private JsonException error(String message, int at) {
        return new JsonException("invalid JSON: " + message + " at offset " + at);
    }
}
