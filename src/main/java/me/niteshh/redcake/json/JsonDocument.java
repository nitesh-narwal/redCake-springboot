package me.niteshh.redcake.json;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The value stored under a key of type {@code ReJSON-RL}: one JSON document and everything {@code JSON.*} can do
 * to it. It has no idea of RESP or of the store; the commands parse their arguments, call a method here and format
 * the answer.
 *
 * <h3>Atomic by construction</h3>
 * An operation that can fail <em>checks everything first and changes the document only afterwards</em>: a command
 * that returns an error leaves no trace, which matters because the server logs and replicates only commands that
 * succeeded - a half-applied change would make the primary differ from its log and from its replicas. Where an
 * operation changes several places (a path with a wildcard) it works through them deepest first, and inside one array
 * from the highest index down (see {@link #processingOrder}), so a match inside another match is changed before the
 * one around it and the array indexes that were selected stay valid.
 *
 * <h3>Memory</h3>
 * {@link #estimatedBytes} is the figure the store uses for {@code maxmemory}. It is kept by adding the change
 * every mutation reports (see {@link JsonObject#put}, {@link JsonArray#add} ...), so it costs nothing per command
 * however large the document is; a test recounts it from scratch after random operations to prove it does not drift.
 *
 * <h3>Not thread-safe</h3>
 * Callers hold the key's lock (the store's {@code read} / {@code mutate}).
 */
public final class JsonDocument {

    /** How a {@code JSON.SET} treats a path that exists or does not. */
    public enum SetMode {
        ALWAYS, ONLY_IF_ABSENT, ONLY_IF_PRESENT
    }

    /** Arithmetic {@code JSON.NUMINCRBY} and {@code JSON.NUMMULTBY} apply. */
    public enum Arithmetic {
        ADD, MULTIPLY
    }

    /** The document object itself. */
    private static final long BASE_BYTES = 48;
    /** A string may not grow beyond this (it would not fit a Java array soon after). */
    static final int MAX_STRING_BYTES = 256 * 1024 * 1024;
    /** A snapshot command carries at most this much JSON text (the parser accepts 1 MiB per argument). */
    static final int SNAPSHOT_CHUNK = 256 * 1024;
    /** A snapshot command appends at most this many values (the parser accepts 1024 words per command). */
    static final int SNAPSHOT_MAX_VALUES = 1000;

    /** {@code null} while the document has not been given its first value. */
    private Object root;
    private long bytes = BASE_BYTES;
    private boolean deleted;

    /** An empty shell the store creates for a key that does not exist yet; the command gives it a root or it vanishes. */
    public JsonDocument() {
    }

    public static JsonDocument of(Object root) {
        JsonDocument document = new JsonDocument();
        document.root = root;
        document.bytes = BASE_BYTES + Json.sizeOf(root);
        return document;
    }

    public Object root() {
        return root;
    }

    /** @return {@code true} until the document has a root value */
    public boolean isUnset() {
        return root == null;
    }

    /** @return {@code true} once the whole document was deleted ({@code JSON.DEL key $}); the store then drops the key */
    public boolean isDeleted() {
        return deleted;
    }

    /** @return the estimated bytes of the document, maintained incrementally: O(1) */
    public long estimatedBytes() {
        return bytes;
    }

    /** @return the same estimate counted from scratch (O(size)); equals {@link #estimatedBytes()} unless there is a bug */
    long recountBytes() {
        return BASE_BYTES + (root == null ? 0 : Json.sizeOf(root));
    }

    // ---- reading ---------------------------------------------------------------------------------------------------

    /** @return what {@code path} selects, in document order (a union may select a value twice) */
    public List<JsonPath.Match> select(JsonPath path) {
        return root == null ? List.of() : path.select(root);
    }

    // ---- JSON.SET / JSON.MERGE -----------------------------------------------------------------------------------

    /**
     * Sets the value at {@code path}. A path that exists is replaced; a path that does not exist is created if its
     * last segment is a plain name and its parent is an object; anything else is an error.
     *
     * @param valueDepth how deeply {@code value} nests (the document may not nest beyond {@link Json#MAX_DEPTH})
     * @return {@code false} if the mode ({@code NX} / {@code XX}) said not to set
     * @throws JsonException if the path does not exist and cannot be created
     */
    public boolean set(JsonPath path, Object value, int valueDepth, SetMode mode) {
        if (root == null) {
            if (!path.isRoot()) {
                throw new JsonException("new objects must be created at the root");
            }
            if (mode == SetMode.ONLY_IF_PRESENT) {
                return false;
            }
            root = value;
            bytes = BASE_BYTES + Json.sizeOf(value);
            return true;
        }
        List<JsonPath.Match> matches = updatable(path);
        if (!matches.isEmpty()) {
            if (mode == SetMode.ONLY_IF_ABSENT) {
                return false;
            }
            checkDepth(matches, valueDepth);
            boolean original = true;
            for (int i : processingOrder(matches)) {
                replace(matches.get(i), original ? value : Json.copy(value));
                original = false;
            }
            return true;
        }
        if (mode == SetMode.ONLY_IF_PRESENT) {
            return false;
        }
        return addMember(path, value, valueDepth);
    }

    /** Adds {@code value} under the last name of {@code path} to every object the rest of the path selects. */
    private boolean addMember(JsonPath path, Object value, int valueDepth) {
        String name = path.lastName();
        if (name == null) {
            throw pathNotFound(path);
        }
        List<JsonPath.Match> parents = new ArrayList<>();
        for (JsonPath.Match match : dedupe(path.parent().select(root))) {
            if (match.value() instanceof JsonObject) {
                parents.add(match);
            }
        }
        if (parents.isEmpty()) {
            throw pathNotFound(path);
        }
        checkDepth(parents, valueDepth + 1);
        boolean original = true;
        for (int i : processingOrder(parents)) {
            bytes += ((JsonObject) parents.get(i).value()).put(name, original ? value : Json.copy(value));
            original = false;
        }
        return true;
    }

    /**
     * Merges {@code patch} into the document at {@code path} like RFC 7386 (JSON Merge Patch): object members are
     * merged recursively, a {@code null} member deletes, everything else (arrays included) replaces.
     *
     * @throws JsonException if the path does not exist and cannot be created
     */
    public void merge(JsonPath path, Object patch, int patchDepth) {
        if (root == null) {
            if (!path.isRoot()) {
                throw new JsonException("new objects must be created at the root");
            }
            root = freshFrom(patch);
            bytes = BASE_BYTES + Json.sizeOf(root);
            return;
        }
        List<JsonPath.Match> matches = updatable(path);
        if (matches.isEmpty()) {
            String name = path.lastName();
            List<JsonPath.Match> parents = new ArrayList<>();
            if (name != null) {
                for (JsonPath.Match match : dedupe(path.parent().select(root))) {
                    if (match.value() instanceof JsonObject) {
                        parents.add(match);
                    }
                }
            }
            if (parents.isEmpty()) {
                throw pathNotFound(path);
            }
            checkDepth(parents, patchDepth + 1);
            if (patch != Json.NULL) {
                for (JsonPath.Match parent : parents) {
                    bytes += ((JsonObject) parent.value()).put(name, freshFrom(patch));
                }
            }
            return;
        }
        checkDepth(matches, patchDepth);
        for (int i : processingOrder(matches)) {
            JsonPath.Match match = matches.get(i);
            if (match.value() instanceof JsonObject target && patch instanceof JsonObject changes) {
                bytes += mergeInto(target, changes);
            } else if (patch == Json.NULL && !match.isRoot()) {
                remove(match); // a null patch deletes the member it is merged into
            } else {
                replace(match, freshFrom(patch));
            }
        }
    }

    /** Merges the members of {@code patch} into {@code target}. @return the change in estimated bytes */
    private long mergeInto(JsonObject target, JsonObject patch) {
        long[] delta = {0};
        patch.forEach((key, value) -> {
            if (value == Json.NULL) {
                Object old = target.get(key);
                if (old != null) {
                    delta[0] -= target.entryBytes(key, old);
                    target.remove(key);
                }
            } else if (value instanceof JsonObject changes && target.get(key) instanceof JsonObject existing) {
                delta[0] += mergeInto(existing, changes);
            } else {
                delta[0] += target.put(KeyPool.intern(key), freshFrom(value));
            }
        });
        return delta[0];
    }

    /** A copy of a patch as a value of its own: members that are {@code null} are dropped, as a merge would. */
    private static Object freshFrom(Object patch) {
        if (patch instanceof JsonObject object) {
            JsonObject copy = new JsonObject();
            object.forEach((key, value) -> {
                if (value != Json.NULL) {
                    copy.putUntracked(key, freshFrom(value));
                }
            });
            copy.trim();
            return copy;
        }
        return Json.copy(patch);
    }

    // ---- JSON.DEL / JSON.CLEAR -----------------------------------------------------------------------------------

    /**
     * Deletes what {@code path} selects. Deleting the root deletes the document ({@link #isDeleted}).
     *
     * @return how many values were deleted
     */
    public int delete(JsonPath path) {
        if (root == null) {
            return 0;
        }
        List<JsonPath.Match> matches = updatable(path);
        int count = 0;
        for (int i : processingOrder(matches)) {
            JsonPath.Match match = matches.get(i);
            if (match.isRoot()) {
                deleted = true;
                return 1;
            }
            remove(match);
            count++;
        }
        return count;
    }

    /**
     * Empties arrays and objects and sets numbers to 0; strings, booleans and nulls are left alone.
     *
     * @return how many values changed (a container that was already empty, or a 0, does not count)
     */
    public int clear(JsonPath path) {
        int count = 0;
        List<JsonPath.Match> matches = updatable(path);
        for (int i : processingOrder(matches)) {
            JsonPath.Match match = matches.get(i);
            switch (match.value()) {
                case JsonObject object -> {
                    if (!object.isEmpty()) {
                        bytes += object.clear();
                        count++;
                    }
                }
                case JsonArray array -> {
                    if (array.size() > 0) {
                        bytes += array.clear();
                        count++;
                    }
                }
                case Long l -> {
                    if (l != 0) {
                        replace(match, 0L);
                        count++;
                    }
                }
                case Double d -> {
                    if (d != 0) {
                        replace(match, 0L);
                        count++;
                    }
                }
                default -> {
                }
            }
        }
        return count;
    }

    // ---- numbers -------------------------------------------------------------------------------------------------

    /**
     * Adds to or multiplies the numbers {@code path} selects. Integers stay integers while they fit 64 bits and
     * become doubles when they do not or when the operand is not an integer.
     *
     * @return the new value of each match, {@code null} for a match that is not a number
     * @throws JsonException if a result is not a finite number (nothing is changed then)
     */
    public List<Object> number(JsonPath path, Arithmetic operation, Number operand) {
        List<JsonPath.Match> matches = updatable(path);
        Object[] results = new Object[matches.size()];
        for (int i = 0; i < results.length; i++) { // plan: nothing is changed until every result is known to be valid
            Object old = matches.get(i).value();
            if (Json.isNumber(old)) {
                results[i] = combine((Number) old, operation, operand);
            }
        }
        for (int i : processingOrder(matches)) {
            if (results[i] != null) {
                replace(matches.get(i), results[i]);
            }
        }
        return Arrays.asList(results);
    }

    private static Object combine(Number a, Arithmetic operation, Number b) {
        if (a instanceof Long x && b instanceof Long y) {
            try {
                return operation == Arithmetic.ADD ? Math.addExact(x, y) : Math.multiplyExact(x, y);
            } catch (ArithmeticException overflow) {
                // does not fit 64 bits: continue as doubles
            }
        }
        double x = a.doubleValue();
        double y = b.doubleValue();
        double result = operation == Arithmetic.ADD ? x + y : x * y;
        if (Double.isNaN(result) || Double.isInfinite(result)) {
            throw new JsonException("the result is not a finite number");
        }
        return result;
    }

    /** Flips the booleans {@code path} selects. @return the new value of each match, {@code null} if not a boolean */
    public List<Object> toggle(JsonPath path) {
        List<JsonPath.Match> matches = updatable(path);
        Object[] results = new Object[matches.size()];
        for (int i : processingOrder(matches)) {
            if (matches.get(i).value() instanceof Boolean flag) {
                results[i] = !flag;
                replace(matches.get(i), results[i]);
            }
        }
        return Arrays.asList(results);
    }

    // ---- strings -------------------------------------------------------------------------------------------------

    /**
     * Appends {@code suffix} to the strings {@code path} selects.
     *
     * @return the new length in bytes of each match, {@code null} for a match that is not a string
     * @throws JsonException if a string would become too long (nothing is changed then)
     */
    public List<Object> appendToStrings(JsonPath path, String suffix) {
        List<JsonPath.Match> matches = updatable(path);
        for (JsonPath.Match match : matches) {
            if (match.value() instanceof String s && (long) s.length() + suffix.length() > MAX_STRING_BYTES) {
                throw new JsonException("the string would be longer than " + MAX_STRING_BYTES + " bytes");
            }
        }
        Object[] results = new Object[matches.size()];
        for (int i : processingOrder(matches)) {
            if (matches.get(i).value() instanceof String s) {
                String joined = s + suffix;
                results[i] = (long) joined.length();
                replace(matches.get(i), joined);
            }
        }
        return Arrays.asList(results);
    }

    // ---- arrays --------------------------------------------------------------------------------------------------

    /**
     * Appends {@code values} to the arrays {@code path} selects.
     *
     * @return the new length of each array, {@code null} for a match that is not an array
     * @throws JsonException if the document would nest too deeply (nothing is changed then)
     */
    public List<Object> appendToArrays(JsonPath path, List<Object> values, int valueDepth) {
        List<JsonPath.Match> matches = updatable(path);
        List<JsonPath.Match> arrays = onlyArrays(matches);
        checkDepth(arrays, valueDepth + 1);
        boolean original = true;
        for (int i : processingOrder(arrays)) {
            JsonArray array = (JsonArray) arrays.get(i).value();
            for (Object value : values) {
                bytes += array.add(original ? value : Json.copy(value));
            }
            original = false;
        }
        return sizes(matches);
    }

    /**
     * Inserts {@code values} before {@code index} (negative counts from the end) in the arrays {@code path} selects.
     *
     * @return the new length of each array, {@code null} for a match that is not an array
     * @throws JsonException if the index is outside an array, or the document would nest too deeply (nothing is
     *                       changed then)
     */
    public List<Object> insertIntoArrays(JsonPath path, long index, List<Object> values, int valueDepth) {
        List<JsonPath.Match> matches = updatable(path);
        List<JsonPath.Match> arrays = onlyArrays(matches);
        checkDepth(arrays, valueDepth + 1);
        for (JsonPath.Match match : arrays) {
            int size = ((JsonArray) match.value()).size();
            long at = index < 0 ? size + index : index;
            if (at < 0 || at > size) {
                throw new JsonException("index out of range");
            }
        }
        boolean original = true;
        for (int i : processingOrder(arrays)) {
            JsonArray array = (JsonArray) arrays.get(i).value();
            int at = (int) (index < 0 ? array.size() + index : index);
            for (Object value : values) {
                bytes += array.insert(at++, original ? value : Json.copy(value));
            }
            original = false;
        }
        return sizes(matches);
    }

    /**
     * The index of the first element equal to {@code value} within {@code [start, stop)} ({@code stop} 0 means the
     * end; negative bounds count from the end), per match: -1 if there is none, {@code null} for a non-array.
     */
    public List<Object> indexInArrays(JsonPath path, Object value, long start, long stop) {
        List<Object> results = new ArrayList<>();
        for (JsonPath.Match match : select(path)) {
            if (match.value() instanceof JsonArray array) {
                int size = array.size();
                long from = start < 0 ? Math.max(0, size + start) : Math.min(start, size);
                long to = stop == 0 ? size : stop < 0 ? Math.max(0, size + stop) : Math.min(stop, size);
                results.add(from >= to ? -1L : (long) array.indexOf(value, (int) from, (int) to));
            } else {
                results.add(null);
            }
        }
        return results;
    }

    /**
     * Removes and returns the element at {@code index} (negative counts from the end, one beyond the ends is moved
     * to the nearest element) of each array {@code path} selects.
     *
     * @return the removed element of each match, {@code null} for an empty array or a match that is not an array
     */
    public List<Object> popFromArrays(JsonPath path, long index) {
        List<JsonPath.Match> matches = updatable(path);
        Object[] results = new Object[matches.size()];
        for (int i : processingOrder(matches)) {
            if (matches.get(i).value() instanceof JsonArray array && array.size() > 0) {
                long at = index < 0 ? array.size() + index : index;
                int clamped = (int) Math.min(Math.max(at, 0), array.size() - 1);
                long freed = array.elementBytes(clamped);
                results[i] = array.remove(clamped);
                bytes -= freed;
            }
        }
        return Arrays.asList(results);
    }

    /**
     * Keeps the elements from {@code start} to {@code stop} inclusive (negative counts from the end) of each array
     * {@code path} selects, and drops the rest.
     *
     * @return the new length of each array, {@code null} for a match that is not an array
     */
    public List<Object> trimArrays(JsonPath path, long start, long stop) {
        List<JsonPath.Match> matches = updatable(path);
        for (int i : processingOrder(matches)) {
            if (matches.get(i).value() instanceof JsonArray array) {
                int size = array.size();
                long from = Math.min(Math.max(start < 0 ? size + start : start, 0), size);
                long to = Math.min(Math.max(stop < 0 ? size + stop : stop, -1), size - 1L) + 1;
                bytes += from >= to ? array.keepRange(0, 0) : array.keepRange((int) from, (int) to);
            }
        }
        return sizes(matches);
    }

    private static List<JsonPath.Match> onlyArrays(List<JsonPath.Match> matches) {
        List<JsonPath.Match> arrays = new ArrayList<>();
        for (JsonPath.Match match : matches) {
            if (match.value() instanceof JsonArray) {
                arrays.add(match);
            }
        }
        return arrays;
    }

    /** The current length of each match that is an array, {@code null} for the others. */
    private static List<Object> sizes(List<JsonPath.Match> matches) {
        List<Object> sizes = new ArrayList<>(matches.size());
        for (JsonPath.Match match : matches) {
            sizes.add(match.value() instanceof JsonArray array ? (Object) (long) array.size() : null);
        }
        return sizes;
    }

    // ---- changing one location ---------------------------------------------------------------------------------------

    private void replace(JsonPath.Match match, Object value) {
        if (match.parent() == null) {
            bytes += Json.sizeOf(value) - Json.sizeOf(root);
            root = value;
        } else if (match.parent() instanceof JsonObject object) {
            bytes += object.put((String) match.key(), value);
        } else {
            bytes += ((JsonArray) match.parent()).set((Integer) match.key(), value);
        }
    }

    /** Removes a member or element (not the root). */
    private void remove(JsonPath.Match match) {
        if (match.parent() instanceof JsonObject object) {
            String key = (String) match.key();
            Object old = object.get(key);
            if (old != null) {
                bytes -= object.entryBytes(key, old);
                object.remove(key);
            }
        } else {
            JsonArray array = (JsonArray) match.parent();
            int index = (Integer) match.key();
            bytes -= array.elementBytes(index);
            array.remove(index);
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    /** The matches to change: each location once, even if a union selected it twice. */
    private List<JsonPath.Match> updatable(JsonPath path) {
        return root == null ? List.of() : dedupe(path.select(root));
    }

    private record Location(Object parent, Object key) {
    }

    /**
     * The order in which to change several matches: deepest first - so a match inside another is changed before the
     * one around it, and nothing is changed inside something already gone - and inside one array from the highest
     * index down, so removing an element does not move one that is still to be removed. (A union such as
     * {@code $.a[3,1]} or a negative step selects in neither order, which is why the list of matches itself cannot be
     * walked from the end.) The matches keep their place in the list because the replies are in that order.
     *
     * @return indexes into {@code matches}
     */
    private static int[] processingOrder(List<JsonPath.Match> matches) {
        int n = matches.size();
        int[] order = new int[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        if (n < 2) {
            return order;
        }
        Integer[] boxed = new Integer[n];
        for (int i = 0; i < n; i++) {
            boxed[i] = i;
        }
        Arrays.sort(boxed, (a, b) -> {
            JsonPath.Match x = matches.get(a);
            JsonPath.Match y = matches.get(b);
            if (x.depth() != y.depth()) {
                return Integer.compare(y.depth(), x.depth());
            }
            int px = System.identityHashCode(x.parent());
            int py = System.identityHashCode(y.parent());
            if (px != py) {
                return Integer.compare(px, py);
            }
            boolean xi = x.key() instanceof Integer;
            boolean yi = y.key() instanceof Integer;
            if (xi && yi) {
                return Integer.compare((Integer) y.key(), (Integer) x.key());
            }
            return xi == yi ? 0 : (xi ? -1 : 1);
        });
        for (int i = 0; i < n; i++) {
            order[i] = boxed[i];
        }
        return order;
    }

    private static List<JsonPath.Match> dedupe(List<JsonPath.Match> matches) {
        if (matches.size() < 2) {
            return matches;
        }
        Set<Location> seen = new HashSet<>();
        List<JsonPath.Match> unique = new ArrayList<>(matches.size());
        for (JsonPath.Match match : matches) {
            if (match.parent() == null || seen.add(new Location(match.parent(), match.key()))) {
                unique.add(match);
            }
        }
        return unique;
    }

    private static void checkDepth(List<JsonPath.Match> matches, int valueDepth) {
        for (JsonPath.Match match : matches) {
            if (match.depth() + valueDepth > Json.MAX_DEPTH) {
                throw new JsonException("the document would nest deeper than " + Json.MAX_DEPTH + " levels");
            }
        }
    }

    static JsonException pathNotFound(JsonPath path) {
        return new JsonException("Path '" + path.text() + "' does not exist");
    }

    // ---- snapshot ------------------------------------------------------------------------------------------------

    /**
     * A document as the commands that rebuild it. Each step is a command name followed by its arguments <em>without
     * the key</em> (the caller inserts it after the name).
     */
    public record Dump(List<List<String>> steps) {
    }

    /**
     * Describes this document as {@code JSON.*} commands: one {@code JSON.SET} when the text is small (the usual
     * case), otherwise the document is built piece by piece so that no command exceeds what the protocol parser
     * accepts: an object becomes {@code JSON.SET path {}} plus one command per member, an array {@code JSON.SET path []}
     * plus {@code JSON.ARRAPPEND} batches, a long string {@code JSON.SET path ""} plus {@code JSON.STRAPPEND} pieces.
     * Replaying the steps - on a replica, or from the append-only file - yields an equal document.
     */
    public Dump dump() {
        List<List<String>> steps = new ArrayList<>();
        if (root != null) {
            emit(steps, "$", root);
        }
        return new Dump(steps);
    }

    private static void emit(List<List<String>> steps, String path, Object node) {
        if (node instanceof String text) {
            emitString(steps, path, text);
            return;
        }
        String text = JsonWriter.toString(node);
        if (text.length() <= SNAPSHOT_CHUNK || !(node instanceof JsonObject || node instanceof JsonArray)) {
            steps.add(List.of("JSON.SET", path, text));
        } else if (node instanceof JsonObject object) {
            steps.add(List.of("JSON.SET", path, "{}"));
            object.forEach((key, value) -> emit(steps, path + "[" + JsonWriter.quote(key) + "]", value));
        } else {
            emitArray(steps, path, (JsonArray) node);
        }
    }

    private static void emitArray(List<List<String>> steps, String path, JsonArray array) {
        steps.add(List.of("JSON.SET", path, "[]"));
        List<String> batch = new ArrayList<>(List.of("JSON.ARRAPPEND", path));
        long batchBytes = 0;
        for (int i = 0, n = array.size(); i < n; i++) {
            Object element = array.get(i);
            String text = JsonWriter.toString(element);
            boolean large = text.length() > SNAPSHOT_CHUNK
                    && (element instanceof JsonObject || element instanceof JsonArray || element instanceof String);
            if (large) {
                flushBatch(steps, batch, path);
                batchBytes = 0;
                steps.add(List.of("JSON.ARRAPPEND", path, "null")); // a place holder, filled in below
                emit(steps, path + "[" + i + "]", element);
                continue;
            }
            if (batch.size() - 2 >= SNAPSHOT_MAX_VALUES || batchBytes + text.length() > SNAPSHOT_CHUNK) {
                flushBatch(steps, batch, path);
                batchBytes = 0;
            }
            batch.add(text);
            batchBytes += text.length();
        }
        flushBatch(steps, batch, path);
    }

    private static void flushBatch(List<List<String>> steps, List<String> batch, String path) {
        if (batch.size() > 2) {
            steps.add(List.copyOf(batch));
            batch.subList(2, batch.size()).clear();
        }
    }

    private static void emitString(List<List<String>> steps, String path, String text) {
        if (text.length() <= SNAPSHOT_CHUNK / 8) {
            steps.add(List.of("JSON.SET", path, JsonWriter.quote(text)));
            return;
        }
        steps.add(List.of("JSON.SET", path, "\"\""));
        int piece = SNAPSHOT_CHUNK / 8; // a character can take up to six bytes once escaped
        for (int start = 0; start < text.length(); ) {
            int end = Math.min(text.length(), start + piece);
            while (end < text.length() && (text.charAt(end) & 0xC0) == 0x80) {
                end--; // never cut a UTF-8 character in two
            }
            steps.add(List.of("JSON.STRAPPEND", path, JsonWriter.quote(text.substring(start, end))));
            start = end;
        }
    }
}
