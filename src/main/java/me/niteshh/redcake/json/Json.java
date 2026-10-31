package me.niteshh.redcake.json;

/**
 * The JSON value model and the few operations every part of the package shares.
 *
 * <p>A JSON value is held as one of
 * <ul>
 *   <li>{@link #NULL}, {@link Boolean};</li>
 *   <li>{@link Long} (a number written without fraction or exponent, within 64 bits) or {@link Double} (every other
 *       number; an integer outside the 64-bit range becomes a double, as it does in most JSON libraries);</li>
 *   <li>{@link String} - a <em>byte string</em> like every value in RedCake (one char per byte, ISO-8859-1): the
 *       UTF-8 bytes the client sent, so a document comes back byte for byte and nothing is decoded or re-encoded;</li>
 *   <li>{@link JsonObject}, {@link JsonArray}.</li>
 * </ul>
 * Java {@code null} never appears inside a document; it means "absent".
 *
 * <p>The sizes returned by {@link #sizeOf} are estimates for {@code maxmemory}: close enough to the JVM's real
 * object sizes to guard the heap, cheap enough to keep up to date incrementally (see {@link JsonDocument}).
 */
public final class Json {

    /** The JSON {@code null}. */
    public static final Object NULL = Null.INSTANCE;

    /** How deeply arrays and objects may nest. serde_json, which RedisJSON uses, stops at the same depth. */
    public static final int MAX_DEPTH = 128;

    // ---- estimated sizes (bytes) -------------------------------------------------------------------------------
    /** A boxed {@link Long} or {@link Double}. */
    static final long NUMBER_BYTES = 16;
    /** String object + byte[] header; the bytes themselves are added. */
    static final long STRING_BASE = 40;
    /** A key's own cost: most keys are shared between documents ({@link KeyPool}), so only part is charged. */
    static final long KEY_BASE = 16;
    static final long OBJECT_SMALL_BASE = 64;
    static final long OBJECT_SMALL_ENTRY = 8;
    static final long OBJECT_LARGE_BASE = 88;
    static final long OBJECT_LARGE_ENTRY = 48;
    static final long ARRAY_BASE = 48;
    /** One slot of an {@code Object[]}, with the slack of its growth policy. */
    static final long ARRAY_GENERIC_SLOT = 5;
    /** One element of a {@code long[]} / {@code double[]} with the same slack. */
    static final long ARRAY_PACKED_SLOT = 10;

    private enum Null {
        INSTANCE;

        @Override
        public String toString() {
            return "null";
        }
    }

    private Json() {
    }

    /** @return the type name {@code JSON.TYPE} reports */
    public static String typeName(Object node) {
        return switch (node) {
            case JsonObject ignored -> "object";
            case JsonArray ignored -> "array";
            case String ignored -> "string";
            case Long ignored -> "integer";
            case Double ignored -> "number";
            case Boolean ignored -> "boolean";
            default -> "null";
        };
    }

    public static boolean isNumber(Object node) {
        return node instanceof Long || node instanceof Double;
    }

    /**
     * JSON equality: objects by their members (order does not matter), arrays element by element, numbers by value
     * (so {@code 1} equals {@code 1.0}: documents written by JavaScript do not tell the two apart).
     */
    public static boolean equal(Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (a instanceof Long x && b instanceof Long y) {
            return x.longValue() == y.longValue();
        }
        if (isNumber(a) && isNumber(b)) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        if (a instanceof String x) {
            return b instanceof String y && x.equals(y);
        }
        if (a instanceof Boolean x) {
            return x.equals(b);
        }
        if (a instanceof JsonObject x) {
            return b instanceof JsonObject y && x.sameMembers(y);
        }
        if (a instanceof JsonArray x) {
            if (!(b instanceof JsonArray y) || x.size() != y.size()) {
                return false;
            }
            for (int i = 0, n = x.size(); i < n; i++) {
                if (!equal(x.get(i), y.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return false; // NULL is a singleton, so identity already answered
    }

    /** A private copy a second location can own: scalars are immutable and shared, containers are copied deeply. */
    public static Object copy(Object node) {
        return switch (node) {
            case JsonObject o -> o.copy();
            case JsonArray a -> a.copy();
            default -> node;
        };
    }

    /** @return the estimated bytes a value takes, everything below it included (O(size of the value)) */
    public static long sizeOf(Object node) {
        return switch (node) {
            case String s -> STRING_BASE + s.length();
            case Long ignored -> NUMBER_BYTES;
            case Double ignored -> NUMBER_BYTES;
            case JsonObject o -> o.bytes();
            case JsonArray a -> a.bytes();
            default -> 0; // NULL and the two Boolean constants are shared
        };
    }

    /** @return how many levels of containers a value has: 0 for a scalar, 1 for {@code []} */
    public static int depthOf(Object node) {
        return switch (node) {
            case JsonObject o -> 1 + o.maxChildDepth();
            case JsonArray a -> 1 + a.maxChildDepth();
            default -> 0;
        };
    }

    /** Serializes compactly: no spaces, no line breaks. */
    public static String toJson(Object node) {
        return JsonWriter.toString(node);
    }
}
