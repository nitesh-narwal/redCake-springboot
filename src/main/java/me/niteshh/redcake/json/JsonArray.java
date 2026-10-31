package me.niteshh.redcake.json;

import java.util.Arrays;

/**
 * A JSON array that stores numbers without boxing them.
 *
 * <p>Embeddings, feature vectors, coordinates and time series are arrays of hundreds or thousands of numbers, and
 * they are exactly what a machine-learning record carries. As {@code Object[]} of {@code Double} such an array costs
 * about 20 bytes per element (a 4-byte reference plus a 16-byte object); as a {@code double[]} it costs 8. So an
 * array that holds only integers is kept as a {@code long[]}, one that holds only non-integral numbers as a
 * {@code double[]}, and any other as an {@code Object[]}. The distinction between integers and doubles is kept
 * (a {@code 1} must not come back as {@code 1.0}), which is why an array that mixes the two is not packed.
 *
 * <p>The representation is invisible to callers: {@link #get} boxes on demand, {@link #set}/{@link #add} accept
 * anything and turn a packed array into a generic one the first time a value does not fit (and an empty array
 * chooses its representation by its first element). Mutators return the change in estimated bytes so a
 * {@link JsonDocument} can keep its total without walking the document. Not thread-safe.
 */
public final class JsonArray {

    static final int GENERIC = 0;
    static final int LONGS = 1;
    static final int DOUBLES = 2;

    private static final Object[] NO_ITEMS = new Object[0];

    private int mode = GENERIC;
    private int size;
    private Object[] items = NO_ITEMS;
    private long[] longs;
    private double[] doubles;

    /** An empty array. */
    public JsonArray() {
    }

    /** Takes ownership of {@code items}, of which the first {@code size} are the elements. */
    static JsonArray ofGeneric(Object[] items, int size) {
        JsonArray array = new JsonArray();
        array.items = items;
        array.size = size;
        return array;
    }

    static JsonArray ofLongs(long[] values, int size) {
        JsonArray array = new JsonArray();
        array.mode = LONGS;
        array.longs = values;
        array.size = size;
        return array;
    }

    static JsonArray ofDoubles(double[] values, int size) {
        JsonArray array = new JsonArray();
        array.mode = DOUBLES;
        array.doubles = values;
        array.size = size;
        return array;
    }

    public int size() {
        return size;
    }

    /** @return the element, boxed if the array is packed */
    public Object get(int index) {
        return switch (mode) {
            case LONGS -> Long.valueOf(longs[index]);
            case DOUBLES -> Double.valueOf(doubles[index]);
            default -> items[index];
        };
    }

    // ---- direct access for the writer, so that serializing a packed array boxes nothing --------------------------

    int mode() {
        return mode;
    }

    long longAt(int index) {
        return longs[index];
    }

    double doubleAt(int index) {
        return doubles[index];
    }

    Object objectAt(int index) {
        return items[index];
    }

    // ---- changes ------------------------------------------------------------------------------------------------

    /**
     * Replaces an element.
     *
     * @return the change in estimated bytes
     */
    public long set(int index, Object value) {
        switch (mode) {
            case LONGS -> {
                if (value instanceof Long l) {
                    longs[index] = l;
                    return 0;
                }
            }
            case DOUBLES -> {
                if (value instanceof Double d) {
                    doubles[index] = d;
                    return 0;
                }
            }
            default -> {
                Object old = items[index];
                items[index] = value;
                return Json.sizeOf(value) - Json.sizeOf(old);
            }
        }
        long delta = unpack();
        Object old = items[index];
        items[index] = value;
        return delta + Json.sizeOf(value) - Json.sizeOf(old);
    }

    /**
     * Appends an element.
     *
     * @return the change in estimated bytes
     */
    public long add(Object value) {
        if (size == 0 && mode == GENERIC) {
            // an empty array takes the representation of its first element
            if (value instanceof Long) {
                mode = LONGS;
                longs = new long[8];
                items = NO_ITEMS;
            } else if (value instanceof Double) {
                mode = DOUBLES;
                doubles = new double[8];
                items = NO_ITEMS;
            }
        }
        switch (mode) {
            case LONGS -> {
                if (value instanceof Long l) {
                    ensureCapacity(size + 1);
                    longs[size++] = l;
                    return Json.ARRAY_PACKED_SLOT;
                }
            }
            case DOUBLES -> {
                if (value instanceof Double d) {
                    ensureCapacity(size + 1);
                    doubles[size++] = d;
                    return Json.ARRAY_PACKED_SLOT;
                }
            }
            default -> {
                ensureCapacity(size + 1);
                items[size++] = value;
                return Json.ARRAY_GENERIC_SLOT + Json.sizeOf(value);
            }
        }
        long delta = unpack();
        ensureCapacity(size + 1);
        items[size++] = value;
        return delta + Json.ARRAY_GENERIC_SLOT + Json.sizeOf(value);
    }

    /**
     * Inserts an element before {@code index} ({@code 0 <= index <= size}).
     *
     * @return the change in estimated bytes
     */
    public long insert(int index, Object value) {
        if (index == size) {
            return add(value);
        }
        long delta = 0;
        if (mode == LONGS && !(value instanceof Long) || mode == DOUBLES && !(value instanceof Double)) {
            delta = unpack();
        }
        ensureCapacity(size + 1);
        switch (mode) {
            case LONGS -> {
                System.arraycopy(longs, index, longs, index + 1, size - index);
                longs[index] = (Long) value;
                delta += Json.ARRAY_PACKED_SLOT;
            }
            case DOUBLES -> {
                System.arraycopy(doubles, index, doubles, index + 1, size - index);
                doubles[index] = (Double) value;
                delta += Json.ARRAY_PACKED_SLOT;
            }
            default -> {
                System.arraycopy(items, index, items, index + 1, size - index);
                items[index] = value;
                delta += Json.ARRAY_GENERIC_SLOT + Json.sizeOf(value);
            }
        }
        size++;
        return delta;
    }

    /** @return the bytes element {@code index} accounts for (see {@link #remove}) */
    public long elementBytes(int index) {
        return mode == GENERIC ? Json.ARRAY_GENERIC_SLOT + Json.sizeOf(items[index]) : Json.ARRAY_PACKED_SLOT;
    }

    /** Removes and returns an element; the bytes it freed are {@link #elementBytes} taken before the call. */
    public Object remove(int index) {
        Object removed = get(index);
        int tail = size - index - 1;
        switch (mode) {
            case LONGS -> System.arraycopy(longs, index + 1, longs, index, tail);
            case DOUBLES -> System.arraycopy(doubles, index + 1, doubles, index, tail);
            default -> {
                System.arraycopy(items, index + 1, items, index, tail);
                items[size - 1] = null;
            }
        }
        size--;
        return removed;
    }

    /**
     * Keeps the elements from {@code from} (inclusive) to {@code to} (exclusive) and drops the rest.
     *
     * @return the change in estimated bytes (zero or negative)
     */
    public long keepRange(int from, int to) {
        if (from == 0 && to == size) {
            return 0;
        }
        long freed = 0;
        int kept = to - from;
        switch (mode) {
            case LONGS -> {
                freed = (long) (size - kept) * Json.ARRAY_PACKED_SLOT;
                System.arraycopy(longs, from, longs, 0, kept);
            }
            case DOUBLES -> {
                freed = (long) (size - kept) * Json.ARRAY_PACKED_SLOT;
                System.arraycopy(doubles, from, doubles, 0, kept);
            }
            default -> {
                for (int i = 0; i < size; i++) {
                    if (i < from || i >= to) {
                        freed += Json.ARRAY_GENERIC_SLOT + Json.sizeOf(items[i]);
                    }
                }
                System.arraycopy(items, from, items, 0, kept);
                Arrays.fill(items, kept, size, null);
            }
        }
        size = kept;
        return -freed;
    }

    /**
     * Removes every element.
     *
     * @return the change in estimated bytes
     */
    public long clear() {
        long before = bytes();
        mode = GENERIC;
        items = NO_ITEMS;
        longs = null;
        doubles = null;
        size = 0;
        return Json.ARRAY_BASE - before;
    }

    // ---- queries ------------------------------------------------------------------------------------------------

    /**
     * @return the index of the first element in {@code [from, to)} that {@link Json#equal equals} {@code value}, or -1
     */
    public int indexOf(Object value, int from, int to) {
        switch (mode) {
            case LONGS -> {
                if (value instanceof Long l) {
                    for (int i = from; i < to; i++) {
                        if (longs[i] == l) {
                            return i;
                        }
                    }
                } else if (value instanceof Double d) {
                    for (int i = from; i < to; i++) {
                        if ((double) longs[i] == d) {
                            return i;
                        }
                    }
                }
                return -1;
            }
            case DOUBLES -> {
                if (Json.isNumber(value)) {
                    double d = ((Number) value).doubleValue();
                    for (int i = from; i < to; i++) {
                        if (doubles[i] == d) {
                            return i;
                        }
                    }
                }
                return -1;
            }
            default -> {
                for (int i = from; i < to; i++) {
                    if (Json.equal(items[i], value)) {
                        return i;
                    }
                }
                return -1;
            }
        }
    }

    /** @return the estimated bytes of this array and everything in it (O(1) if packed, O(size) otherwise) */
    long bytes() {
        long total = Json.ARRAY_BASE + (mode == GENERIC ? Json.ARRAY_GENERIC_SLOT : Json.ARRAY_PACKED_SLOT) * size;
        if (mode == GENERIC) {
            for (int i = 0; i < size; i++) {
                total += Json.sizeOf(items[i]);
            }
        }
        return total;
    }

    JsonArray copy() {
        JsonArray copy = new JsonArray();
        copy.mode = mode;
        copy.size = size;
        switch (mode) {
            case LONGS -> copy.longs = Arrays.copyOf(longs, size);
            case DOUBLES -> copy.doubles = Arrays.copyOf(doubles, size);
            default -> {
                copy.items = size == 0 ? NO_ITEMS : new Object[size];
                for (int i = 0; i < size; i++) {
                    copy.items[i] = Json.copy(items[i]);
                }
            }
        }
        return copy;
    }

    int maxChildDepth() {
        int deepest = 0;
        if (mode == GENERIC) {
            for (int i = 0; i < size; i++) {
                deepest = Math.max(deepest, Json.depthOf(items[i]));
            }
        }
        return deepest;
    }

    // ---- internals ----------------------------------------------------------------------------------------------

    private void ensureCapacity(int needed) {
        int capacity = switch (mode) {
            case LONGS -> longs.length;
            case DOUBLES -> doubles.length;
            default -> items.length;
        };
        if (needed <= capacity) {
            return;
        }
        int grown = Math.max(needed, Math.max(8, capacity + (capacity >> 1)));
        switch (mode) {
            case LONGS -> longs = Arrays.copyOf(longs, grown);
            case DOUBLES -> doubles = Arrays.copyOf(doubles, grown);
            default -> items = Arrays.copyOf(items, grown);
        }
    }

    /** Turns a packed array into a generic one. @return the change in estimated bytes */
    private long unpack() {
        Object[] boxed = new Object[Math.max(size, 4)];
        if (mode == LONGS) {
            for (int i = 0; i < size; i++) {
                boxed[i] = Long.valueOf(longs[i]);
            }
        } else {
            for (int i = 0; i < size; i++) {
                boxed[i] = Double.valueOf(doubles[i]);
            }
        }
        items = boxed;
        longs = null;
        doubles = null;
        mode = GENERIC;
        return (long) size * (Json.ARRAY_GENERIC_SLOT + Json.NUMBER_BYTES - Json.ARRAY_PACKED_SLOT);
    }

    /** @return {@code true} if the numbers are stored unboxed (for tests) */
    boolean isPacked() {
        return mode != GENERIC;
    }
}
