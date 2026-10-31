package me.niteshh.redcake.json;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link JsonObject} and {@link JsonArray} against plain Java collections, under random sequences of operations:
 * same contents and order after every step, and the byte total kept from the deltas the mutators return equal to
 * the total counted from scratch.
 */
class JsonContainersTest {

    private static Object randomScalar(Random random) {
        return switch (random.nextInt(7)) {
            case 0 -> (long) random.nextInt(100) - 50;
            case 1 -> random.nextGaussian();
            case 2 -> "s" + random.nextInt(1000) + "x".repeat(random.nextInt(30));
            case 3 -> Boolean.valueOf(random.nextBoolean());
            case 4 -> Json.NULL;
            case 5 -> random.nextLong();
            default -> random.nextInt(1000) * 0.5;
        };
    }

    private static Object randomValue(Random random, int depth) {
        if (depth < 2 && random.nextInt(8) == 0) {
            if (random.nextBoolean()) {
                JsonObject object = new JsonObject();
                int n = random.nextInt(4);
                for (int i = 0; i < n; i++) {
                    object.put("k" + random.nextInt(10), randomValue(random, depth + 1));
                }
                return object;
            }
            JsonArray array = new JsonArray();
            int n = random.nextInt(5);
            for (int i = 0; i < n; i++) {
                array.add(randomValue(random, depth + 1));
            }
            return array;
        }
        return randomScalar(random);
    }

    /** Same JSON value, and the same Java type for numbers (an integer is not a double). */
    private static void assertSameValue(Object expected, Object actual, String where) {
        assertTrue(Json.equal(expected, actual), () -> where + ": " + JsonWriter.toString(expected) + " vs " + JsonWriter.toString(actual));
        if (expected instanceof Long || expected instanceof Double) {
            assertEquals(expected.getClass(), actual.getClass(), where);
        }
    }

    // ------------------------------------------------------------------------------------------------ objects

    @Test
    void anObjectBehavesLikeALinkedHashMap() {
        for (int seed = 0; seed < 60; seed++) {
            Random random = new Random(seed);
            JsonObject object = new JsonObject();
            Map<String, Object> model = new LinkedHashMap<>();
            long bytes = object.bytes();
            int keySpace = seed % 3 == 0 ? 6 : seed % 3 == 1 ? 30 : 400; // small, around the switch, far beyond it

            for (int step = 0; step < 400; step++) {
                String key = "key" + random.nextInt(keySpace);
                switch (random.nextInt(10)) {
                    case 0, 1, 2, 3, 4 -> {
                        Object value = randomValue(random, 0);
                        bytes += object.put(key, value);
                        model.put(key, value);
                    }
                    case 5, 6, 7 -> {
                        Object old = object.get(key);
                        long entry = old == null ? 0 : object.entryBytes(key, old);
                        assertSame(model.get(key), old);
                        assertSame(model.remove(key), object.remove(key));
                        bytes -= entry;
                    }
                    case 8 -> assertSame(model.get(key), object.get(key));
                    default -> {
                        if (random.nextInt(20) == 0) {
                            bytes += object.clear();
                            model.clear();
                        }
                    }
                }
                assertEquals(model.size(), object.size());
                assertEquals(bytes, object.bytes(), "seed " + seed + " step " + step);
            }

            List<String> keys = new ArrayList<>();
            List<Object> values = new ArrayList<>();
            object.forEach((k, v) -> {
                keys.add(k);
                values.add(v);
            });
            assertEquals(new ArrayList<>(model.keySet()), keys, "insertion order, seed " + seed);
            assertEquals(new ArrayList<>(model.values()), values);
            assertEquals(keys, object.keyList());
            if (model.size() > JsonObject.SMALL_MAX) {
                assertTrue(object.isLarge(), "more than " + JsonObject.SMALL_MAX + " members need the map");
            }
        }
    }

    @Test
    void anObjectSwitchesToAMapBeyondSixteenMembersAndKeepsItsOrder() {
        JsonObject object = new JsonObject();
        for (int i = 0; i < JsonObject.SMALL_MAX; i++) {
            object.put("k" + i, (long) i);
        }
        assertFalse(object.isLarge());
        object.put("k0", 99L); // replacing keeps the place
        object.put("extra", 1L);
        assertTrue(object.isLarge());
        List<String> keys = object.keyList();
        assertEquals("k0", keys.get(0));
        assertEquals("extra", keys.get(keys.size() - 1));
        assertEquals(99L, object.get("k0"));
        assertEquals(JsonObject.SMALL_MAX + 1, object.size());
    }

    @Test
    void copiesAreIndependentAndEqual() {
        JsonObject original = (JsonObject) JsonParser.parse("{\"a\":[1,2,{\"b\":[3]}],\"c\":{\"d\":\"e\"}}").value();
        JsonObject copy = (JsonObject) Json.copy(original);
        assertTrue(Json.equal(original, copy));
        ((JsonArray) copy.get("a")).add(4L);
        ((JsonObject) copy.get("c")).put("f", 1L);
        assertFalse(Json.equal(original, copy));
        assertEquals(3, ((JsonArray) original.get("a")).size());
        assertEquals(1, ((JsonObject) original.get("c")).size());
    }

    // ------------------------------------------------------------------------------------------------- arrays

    private static void assertSameArray(List<Object> model, JsonArray array, String where) {
        assertEquals(model.size(), array.size(), where);
        for (int i = 0; i < model.size(); i++) {
            assertSameValue(model.get(i), array.get(i), where + " [" + i + "]");
        }
        if (array.isPacked()) {
            Class<?> type = model.isEmpty() ? null : model.get(0).getClass();
            for (Object element : model) {
                assertSame(type, element.getClass(), where + ": a packed array holds one kind of number");
            }
        }
    }

    @Test
    void anArrayBehavesLikeAnArrayList() {
        for (int seed = 0; seed < 150; seed++) {
            Random random = new Random(seed);
            JsonArray array = new JsonArray();
            List<Object> model = new ArrayList<>();
            long bytes = array.bytes();
            // some runs only ever add numbers of one kind, so that the packed representations are exercised for long
            int style = seed % 4;

            for (int step = 0; step < 300; step++) {
                Object value = switch (style) {
                    case 0 -> (long) random.nextInt(1000);
                    case 1 -> random.nextGaussian();
                    case 2 -> random.nextInt(25) == 0 ? randomValue(random, 0) : (long) random.nextInt(1000);
                    default -> randomValue(random, 0);
                };
                int size = model.size();
                switch (random.nextInt(12)) {
                    case 0, 1, 2, 3 -> {
                        bytes += array.add(value);
                        model.add(value);
                    }
                    case 4, 5 -> {
                        int at = random.nextInt(size + 1);
                        bytes += array.insert(at, value);
                        model.add(at, value);
                    }
                    case 6, 7 -> {
                        if (size > 0) {
                            int at = random.nextInt(size);
                            bytes += array.set(at, value);
                            model.set(at, value);
                        }
                    }
                    case 8, 9 -> {
                        if (size > 0) {
                            int at = random.nextInt(size);
                            long element = array.elementBytes(at);
                            assertSameValue(model.remove(at), array.remove(at), "removed");
                            bytes -= element;
                        }
                    }
                    case 10 -> {
                        if (size > 0 && random.nextInt(4) == 0) {
                            int from = random.nextInt(size);
                            int to = from + random.nextInt(size - from + 1);
                            bytes += array.keepRange(from, to);
                            model = new ArrayList<>(model.subList(from, to));
                        }
                    }
                    default -> {
                        if (random.nextInt(30) == 0) {
                            bytes += array.clear();
                            model.clear();
                        } else if (size > 0) {
                            Object probe = model.get(random.nextInt(size));
                            int from = random.nextInt(size);
                            int expected = -1;
                            for (int i = from; i < size; i++) {
                                if (Json.equal(model.get(i), probe)) {
                                    expected = i;
                                    break;
                                }
                            }
                            assertEquals(expected, array.indexOf(probe, from, size), "indexOf");
                        }
                    }
                }
                assertEquals(bytes, array.bytes(), "bytes, seed " + seed + " step " + step);
                if (step % 25 == 0) {
                    assertSameArray(model, array, "seed " + seed + " step " + step);
                }
            }
            assertSameArray(model, array, "seed " + seed + " end");
        }
    }

    @Test
    void anEmptyArrayChoosesItsRepresentationByItsFirstElementAndFallsBackWhenAValueDoesNotFit() {
        JsonArray ints = new JsonArray();
        ints.add(1L);
        ints.add(2L);
        assertTrue(ints.isPacked());
        ints.add(2.5);
        assertFalse(ints.isPacked(), "a double does not fit a long[]");
        assertEquals(Long.class, ints.get(0).getClass(), "the integers stay integers");
        assertEquals(Double.class, ints.get(2).getClass());

        JsonArray doubles = new JsonArray();
        doubles.add(0.5);
        doubles.add(1.5);
        assertTrue(doubles.isPacked());
        doubles.set(0, "x");
        assertFalse(doubles.isPacked());
        assertEquals("x", doubles.get(0));
        assertEquals(1.5, doubles.get(1));

        JsonArray text = new JsonArray();
        text.add("a");
        text.add(1L);
        assertFalse(text.isPacked());
    }

    @Test
    void numbersCompareByValueAcrossTypes() {
        JsonArray ints = (JsonArray) JsonParser.parse("[1,2,3]").value();
        assertEquals(1, ints.indexOf(2.0, 0, 3), "2.0 equals 2");
        assertEquals(-1, ints.indexOf(2.5, 0, 3));
        assertEquals(-1, ints.indexOf("2", 0, 3));
        JsonArray doubles = (JsonArray) JsonParser.parse("[0.5,2.5,3.0]").value();
        assertEquals(2, doubles.indexOf(3L, 0, 3));
        assertTrue(Json.equal(1L, 1.0));
        assertFalse(Json.equal(1L, "1"));
        assertFalse(Json.equal(Json.NULL, Boolean.FALSE));
        assertTrue(Json.equal(JsonParser.parse("{\"a\":1,\"b\":[1,2]}").value(), JsonParser.parse("{\"b\":[1,2.0],\"a\":1}").value()));
        assertFalse(Json.equal(JsonParser.parse("[1,2]").value(), JsonParser.parse("[2,1]").value()));
    }

    @Test
    void depthAndEstimatedSizeOfAParsedDocument() {
        assertEquals(0, Json.depthOf(1L));
        assertEquals(1, Json.depthOf(new JsonArray()));
        assertEquals(3, Json.depthOf(JsonParser.parse("{\"a\":[{\"b\":1}]}").value()));
        // a 384-number embedding costs 8 bytes a number plus the header, not 20
        JsonArray embedding = new JsonArray();
        for (int i = 0; i < 384; i++) {
            embedding.add(i * 0.001);
        }
        assertTrue(embedding.isPacked());
        assertTrue(Json.sizeOf(embedding) < 384 * 11 + 100, "estimated " + Json.sizeOf(embedding));
    }
}
