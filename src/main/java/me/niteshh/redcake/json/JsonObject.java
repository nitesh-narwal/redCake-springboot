package me.niteshh.redcake.json;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A JSON object: members in insertion order, replacing a member keeps its place.
 *
 * <p>Two representations, switched automatically. Records have a handful of members, and for them a
 * {@code LinkedHashMap} is mostly overhead (a table, one 40-byte entry per member): up to {@value #SMALL_MAX} members
 * the object is two parallel arrays searched linearly, which is as fast at that size and a fraction of the memory.
 * Beyond that - dictionary-like objects with thousands of members - it becomes a {@code LinkedHashMap}, where lookup
 * and removal stay O(1). It never switches back.
 *
 * <p>Mutators that change the size of a document return the change in estimated bytes (see {@link Json#sizeOf}), so
 * {@link JsonDocument} can keep its total up to date without walking the document after every command. Not
 * thread-safe: a document is only touched under its key's lock.
 */
public final class JsonObject {

    /** Members up to which the arrays are used. */
    static final int SMALL_MAX = 16;

    /** Receives the members in order. */
    @FunctionalInterface
    public interface Visitor {
        void visit(String key, Object value);
    }

    private static final String[] NO_KEYS = new String[0];
    private static final Object[] NO_VALUES = new Object[0];

    private String[] keys = NO_KEYS;
    private Object[] values = NO_VALUES;
    /** Members while small; unused once {@link #map} exists. */
    private int size;
    private LinkedHashMap<String, Object> map;

    public int size() {
        return map != null ? map.size() : size;
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    /** @return the member's value, or {@code null} if there is no such member */
    public Object get(String key) {
        if (map != null) {
            return map.get(key);
        }
        int i = indexOf(key);
        return i < 0 ? null : values[i];
    }

    private int indexOf(String key) {
        String[] names = keys;
        for (int i = 0, n = size; i < n; i++) {
            String name = names[i];
            if (name == key || name.equals(key)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Adds a member or replaces its value, without accounting for memory. For the parser, which builds a whole
     * value and measures it once.
     */
    void putUntracked(String key, Object value) {
        if (map != null) {
            map.put(key, value);
            return;
        }
        int i = indexOf(key);
        if (i >= 0) {
            values[i] = value;
            return;
        }
        if (size == SMALL_MAX) {
            toLarge();
            map.put(key, value);
            return;
        }
        append(key, value);
    }

    /** Adds a member that is known to be new while the object is small and has room under {@link #SMALL_MAX}. */
    private void append(String key, Object value) {
        if (size == keys.length) {
            int capacity = keys.length == 0 ? 4 : Math.min(SMALL_MAX, keys.length * 2);
            keys = Arrays.copyOf(keys, capacity);
            values = Arrays.copyOf(values, capacity);
        }
        keys[size] = key;
        values[size] = value;
        size++;
    }

    /** Gives the arrays exactly the size they need (the parser calls it once an object is complete). */
    void trim() {
        if (map == null && keys.length > size) {
            keys = size == 0 ? NO_KEYS : Arrays.copyOf(keys, size);
            values = size == 0 ? NO_VALUES : Arrays.copyOf(values, size);
        }
    }

    private void toLarge() {
        LinkedHashMap<String, Object> large = new LinkedHashMap<>(size * 2 + 2);
        for (int i = 0; i < size; i++) {
            large.put(keys[i], values[i]);
        }
        map = large;
        keys = NO_KEYS;
        values = NO_VALUES;
        size = 0;
    }

    /**
     * Adds a member or replaces its value (a replaced member keeps its position).
     *
     * @return the change in estimated bytes
     */
    public long put(String key, Object value) {
        if (map != null) {
            Object old = map.put(key, value);
            return old != null
                    ? Json.sizeOf(value) - Json.sizeOf(old)
                    : Json.OBJECT_LARGE_ENTRY + keyBytes(key) + Json.sizeOf(value);
        }
        int i = indexOf(key);
        if (i >= 0) {
            Object old = values[i];
            values[i] = value;
            return Json.sizeOf(value) - Json.sizeOf(old);
        }
        long delta = 0;
        if (size == SMALL_MAX) {
            // it becomes a map: the fixed part and the slot of every member grow
            delta = (Json.OBJECT_LARGE_BASE + Json.OBJECT_LARGE_ENTRY * size)
                    - (Json.OBJECT_SMALL_BASE + Json.OBJECT_SMALL_ENTRY * size);
            toLarge();
            map.put(key, value);
            return delta + Json.OBJECT_LARGE_ENTRY + keyBytes(key) + Json.sizeOf(value);
        }
        append(key, value);
        return Json.OBJECT_SMALL_ENTRY + keyBytes(key) + Json.sizeOf(value);
    }

    /** @return the removed value, or {@code null} if there was no such member; see {@link #entryBytes} for its size */
    public Object remove(String key) {
        if (map != null) {
            return map.remove(key);
        }
        int i = indexOf(key);
        if (i < 0) {
            return null;
        }
        Object old = values[i];
        int tail = size - i - 1;
        System.arraycopy(keys, i + 1, keys, i, tail);
        System.arraycopy(values, i + 1, values, i, tail);
        size--;
        keys[size] = null;
        values[size] = null;
        return old;
    }

    /** @return the bytes one member accounts for in this object (its slot, its key and everything below its value) */
    public long entryBytes(String key, Object value) {
        return (map != null ? Json.OBJECT_LARGE_ENTRY : Json.OBJECT_SMALL_ENTRY) + keyBytes(key) + Json.sizeOf(value);
    }

    static long keyBytes(String key) {
        return Json.KEY_BASE + key.length();
    }

    /**
     * Removes every member.
     *
     * @return the change in estimated bytes
     */
    public long clear() {
        long before = bytes();
        map = null;
        keys = NO_KEYS;
        values = NO_VALUES;
        size = 0;
        return Json.OBJECT_SMALL_BASE - before;
    }

    /** Visits the members in order. The object must not be modified meanwhile. */
    public void forEach(Visitor visitor) {
        if (map != null) {
            for (Map.Entry<String, Object> e : map.entrySet()) {
                visitor.visit(e.getKey(), e.getValue());
            }
            return;
        }
        for (int i = 0, n = size; i < n; i++) {
            visitor.visit(keys[i], values[i]);
        }
    }

    /** @return the member names in order, as a list the caller may keep while it changes the object */
    public List<String> keyList() {
        if (map != null) {
            return new ArrayList<>(map.keySet());
        }
        return new ArrayList<>(Arrays.asList(keys).subList(0, size));
    }

    /** @return this object with every member copied (see {@link Json#copy}) */
    JsonObject copy() {
        JsonObject copy = new JsonObject();
        if (map != null) {
            LinkedHashMap<String, Object> members = new LinkedHashMap<>(map.size() * 2);
            for (Map.Entry<String, Object> e : map.entrySet()) {
                members.put(e.getKey(), Json.copy(e.getValue()));
            }
            copy.map = members;
        } else {
            copy.keys = size == 0 ? NO_KEYS : Arrays.copyOf(keys, size);
            copy.values = new Object[size];
            for (int i = 0; i < size; i++) {
                copy.values[i] = Json.copy(values[i]);
            }
            copy.size = size;
        }
        return copy;
    }

    /** Same members with equal values, in any order. */
    boolean sameMembers(JsonObject other) {
        if (size() != other.size()) {
            return false;
        }
        boolean[] same = {true};
        forEach((key, value) -> {
            if (same[0]) {
                Object counterpart = other.get(key);
                same[0] = counterpart != null && Json.equal(value, counterpart);
            }
        });
        return same[0];
    }

    int maxChildDepth() {
        int[] deepest = {0};
        forEach((key, value) -> deepest[0] = Math.max(deepest[0], Json.depthOf(value)));
        return deepest[0];
    }

    /** @return the estimated bytes of this object and everything in it (O(members)) */
    long bytes() {
        long total;
        if (map != null) {
            total = Json.OBJECT_LARGE_BASE;
            for (Map.Entry<String, Object> e : map.entrySet()) {
                total += Json.OBJECT_LARGE_ENTRY + keyBytes(e.getKey()) + Json.sizeOf(e.getValue());
            }
        } else {
            total = Json.OBJECT_SMALL_BASE;
            for (int i = 0; i < size; i++) {
                total += Json.OBJECT_SMALL_ENTRY + keyBytes(keys[i]) + Json.sizeOf(values[i]);
            }
        }
        return total;
    }

    /** @return {@code true} once the members live in a hash map (for tests) */
    boolean isLarge() {
        return map != null;
    }
}
