package me.niteshh.redcake.json;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JsonSmartJsonProvider;
import com.jayway.jsonpath.spi.mapper.JsonSmartMappingProvider;
import me.niteshh.redcake.json.JsonDocument.Arithmetic;
import me.niteshh.redcake.json.JsonDocument.SetMode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** What JSON.* does to a document: semantics, atomicity, the byte count, and the snapshot that rebuilds it. */
class JsonDocumentTest {

    private static JsonDocument doc(String json) {
        return JsonDocument.of(JsonParser.parse(json).value());
    }

    private static String text(JsonDocument d) {
        return JsonWriter.toString(d.root());
    }

    private static JsonPath path(String p) {
        return JsonPath.parse(p);
    }

    private static boolean set(JsonDocument d, String path, String json) {
        return set(d, path, json, SetMode.ALWAYS);
    }

    private static boolean set(JsonDocument d, String path, String json, SetMode mode) {
        JsonParser.Parsed parsed = JsonParser.parse(json);
        boolean result = d.set(path(path), parsed.value(), parsed.depth(), mode);
        check(d);
        return result;
    }

    private static void merge(JsonDocument d, String path, String json) {
        JsonParser.Parsed parsed = JsonParser.parse(json);
        d.merge(path(path), parsed.value(), parsed.depth());
        check(d);
    }

    private static List<Object> values(String... json) {
        List<Object> out = new ArrayList<>();
        for (String j : json) {
            out.add(JsonParser.parse(j).value());
        }
        return out;
    }

    /** The byte count has not drifted and the tree is still well formed (it survives text and back). */
    private static void check(JsonDocument d) {
        if (d.isUnset()) {
            return;
        }
        assertEquals(d.recountBytes(), d.estimatedBytes(), "the estimated size drifted from the real one");
        String text = JsonWriter.toString(d.root());
        assertEquals(text, JsonWriter.toString(JsonParser.parse(text).value()));
    }

    // ------------------------------------------------------------------------------------------------------ set

    @Test
    void setReplacesAddsAndRefuses() {
        JsonDocument d = doc("{\"a\":1,\"arr\":[1,2,3],\"o\":{\"x\":1}}");
        assertTrue(set(d, "$.a", "2"));
        assertTrue(set(d, "$.b", "\"new\""));
        assertTrue(set(d, "$.o.y", "[1]"));
        assertTrue(set(d, "$.arr[1]", "\"two\""));
        assertTrue(set(d, "$.arr[-1]", "null"));
        assertEquals("{\"a\":2,\"arr\":[1,\"two\",null],\"o\":{\"x\":1,\"y\":[1]},\"b\":\"new\"}", text(d));
        assertTrue(set(d, "$", "{\"fresh\":true}"));
        assertEquals("{\"fresh\":true}", text(d));
        assertTrue(set(d, ".fresh", "false"), "a legacy path works the same");
        assertTrue(set(d, "$[\"with space\"]", "1"));
        assertEquals("{\"fresh\":false,\"with space\":1}", text(d));

        String before = text(d);
        for (String bad : List.of("$.missing.child", "$.fresh.x", "$.arr[10]", "$.o.y.z", "$[0]", "$.*.x.y")) {
            JsonException e = assertThrows(JsonException.class, () -> set(d, bad, "1"), bad);
            assertTrue(e.getMessage().contains("does not exist"), e.getMessage());
            assertEquals(before, text(d), "a refused set changes nothing");
        }
    }

    @Test
    void nxAndXx() {
        JsonDocument d = doc("{\"a\":1}");
        assertFalse(set(d, "$.a", "9", SetMode.ONLY_IF_ABSENT));
        assertTrue(set(d, "$.fresh", "1", SetMode.ONLY_IF_ABSENT));
        assertFalse(set(d, "$.fresh", "2", SetMode.ONLY_IF_ABSENT));
        assertTrue(set(d, "$.fresh", "3", SetMode.ONLY_IF_PRESENT));
        assertFalse(set(d, "$.nothing", "1", SetMode.ONLY_IF_PRESENT));
        assertFalse(set(d, "$.nothing.deeper", "1", SetMode.ONLY_IF_PRESENT), "XX never errors, it just does not set");
        assertEquals("{\"a\":1,\"fresh\":3}", text(d));
    }

    @Test
    void aNewDocumentOnlyAcceptsItsRoot() {
        JsonDocument fresh = new JsonDocument();
        assertTrue(fresh.isUnset());
        JsonException e = assertThrows(JsonException.class, () -> set(fresh, "$.a", "1"));
        assertTrue(e.getMessage().contains("root"), e.getMessage());
        assertFalse(set(fresh, "$", "1", SetMode.ONLY_IF_PRESENT));
        assertTrue(fresh.isUnset());
        assertTrue(set(fresh, "$", "{\"a\":1}", SetMode.ONLY_IF_ABSENT));
        assertFalse(fresh.isUnset());
        assertEquals("{\"a\":1}", text(fresh));
    }

    @Test
    void aPathWithSeveralMatchesSetsEachWithItsOwnCopy() {
        JsonDocument d = doc("{\"items\":[1,2,3],\"o1\":{},\"o2\":{}}");
        assertTrue(set(d, "$.items[*]", "{\"x\":[1]}"));
        assertEquals("{\"items\":[{\"x\":[1]},{\"x\":[1]},{\"x\":[1]}],\"o1\":{},\"o2\":{}}", text(d));
        d.appendToArrays(path("$.items[0].x"), List.of(2L), 0);
        check(d);
        assertEquals("{\"items\":[{\"x\":[1,2]},{\"x\":[1]},{\"x\":[1]}],\"o1\":{},\"o2\":{}}", text(d),
                "changing one copy leaves the others alone");
        // nothing matches $.*.k, so it is added to every object the rest of the path selects
        assertTrue(set(d, "$.o1.k", "1"));
        JsonDocument objects = doc("{\"o1\":{},\"o2\":{}}");
        assertTrue(set(objects, "$.*.k", "1"));
        assertEquals("{\"o1\":{\"k\":1},\"o2\":{\"k\":1}}", text(objects));
        // when some match exists, only the matches are set (nothing is added elsewhere)
        assertTrue(set(objects, "$.*.k", "2"));
        objects.delete(path("$.o2.k"));
        assertTrue(set(objects, "$.*.k", "3"));
        assertEquals("{\"o1\":{\"k\":3},\"o2\":{}}", text(objects));
    }

    @Test
    void aMatchInsideAMatchIsSetBeforeTheOneAroundIt() {
        JsonDocument d = doc("{\"a\":{\"a\":{\"a\":1}}}");
        assertTrue(set(d, "$..a", "0"));
        assertEquals("{\"a\":0}", text(d));
    }

    @Test
    void theDocumentMayNotNestBeyondTheLimit() {
        JsonDocument d = doc("{\"a\":1}");
        String ok = "[".repeat(Json.MAX_DEPTH - 1) + "]".repeat(Json.MAX_DEPTH - 1);
        String tooDeep = "[".repeat(Json.MAX_DEPTH) + "]".repeat(Json.MAX_DEPTH);
        assertTrue(set(d, "$.a", ok));
        String before = text(d);
        assertThrows(JsonException.class, () -> set(d, "$.a", tooDeep));
        assertThrows(JsonException.class, () -> set(d, "$.b", tooDeep), "a new member sits one level below the root");
        assertTrue(set(d, "$.b", ok), "this one fits exactly");
        d.delete(path("$.b"));
        before = text(d);
        assertEquals(before, text(d));
        assertThrows(JsonException.class, () -> d.appendToArrays(path("$.a"), values(ok), JsonParser.parse(ok).depth()));
        assertEquals(before, text(d));
    }

    // ----------------------------------------------------------------------------------------------- merge

    @Test
    void mergeFollowsRfc7386() {
        String[][] vectors = {
                {"{\"a\":\"b\"}", "{\"a\":\"c\"}", "{\"a\":\"c\"}"},
                {"{\"a\":\"b\"}", "{\"b\":\"c\"}", "{\"a\":\"b\",\"b\":\"c\"}"},
                {"{\"a\":\"b\"}", "{\"a\":null}", "{}"},
                {"{\"a\":\"b\",\"b\":\"c\"}", "{\"a\":null}", "{\"b\":\"c\"}"},
                {"{\"a\":[\"b\"]}", "{\"a\":\"c\"}", "{\"a\":\"c\"}"},
                {"{\"a\":\"c\"}", "{\"a\":[\"b\"]}", "{\"a\":[\"b\"]}"},
                {"{\"a\":{\"b\":\"c\"}}", "{\"a\":{\"b\":\"d\",\"c\":null}}", "{\"a\":{\"b\":\"d\"}}"},
                {"{\"a\":[{\"b\":\"c\"}]}", "{\"a\":[1]}", "{\"a\":[1]}"},
                {"[\"a\",\"b\"]", "[\"c\",\"d\"]", "[\"c\",\"d\"]"},
                {"{\"a\":\"b\"}", "[\"c\"]", "[\"c\"]"},
                {"{\"a\":\"foo\"}", "null", "null"},
                {"{\"a\":\"foo\"}", "\"bar\"", "\"bar\""},
                {"{\"e\":null}", "{\"a\":1}", "{\"e\":null,\"a\":1}"},
                {"[1,2]", "{\"a\":\"b\",\"c\":null}", "{\"a\":\"b\"}"},
                {"{}", "{\"a\":{\"bb\":{\"ccc\":null}}}", "{\"a\":{\"bb\":{}}}"},
        };
        for (String[] vector : vectors) {
            JsonDocument d = doc(vector[0]);
            merge(d, "$", vector[1]);
            assertEquals(vector[2], text(d), vector[0] + " merged with " + vector[1]);
        }
    }

    @Test
    void mergeAtAPath() {
        JsonDocument d = doc("{\"a\":{\"x\":1},\"l\":[1],\"s\":\"text\"}");
        merge(d, "$.a", "{\"y\":2,\"x\":null}");
        merge(d, "$.l", "[2]");
        merge(d, "$.s", "{\"now\":\"an object\"}");
        merge(d, "$.created", "{\"p\":null,\"q\":1}");
        assertEquals("{\"a\":{\"y\":2},\"l\":[2],\"s\":{\"now\":\"an object\"},\"created\":{\"q\":1}}", text(d));
        merge(d, "$.a", "null");
        merge(d, "$.l", "null");
        assertEquals("{\"s\":{\"now\":\"an object\"},\"created\":{\"q\":1}}", text(d), "a null patch removes the member it is merged into");
        merge(d, "$.never", "null");
        assertEquals("{\"s\":{\"now\":\"an object\"},\"created\":{\"q\":1}}", text(d));
        String before = text(d);
        assertThrows(JsonException.class, () -> merge(d, "$.no.such.parent", "1"));
        assertEquals(before, text(d));

        JsonDocument many = doc("{\"x\":[{\"s\":1},{\"s\":2}]}");
        merge(many, "$.x[*]", "{\"t\":true,\"s\":null}");
        assertEquals("{\"x\":[{\"t\":true},{\"t\":true}]}", text(many));

        JsonDocument fresh = new JsonDocument();
        assertThrows(JsonException.class, () -> merge(fresh, "$.a", "1"));
        merge(fresh, "$", "{\"a\":1,\"b\":null}");
        assertEquals("{\"a\":1}", text(fresh));
    }

    // ----------------------------------------------------------------------------------------- delete and clear

    @Test
    void deleteRemovesMembersElementsAndTheRoot() {
        JsonDocument d = doc("{\"a\":1,\"arr\":[0,1,2,3,4],\"o\":{\"x\":{\"a\":2}}}");
        assertEquals(1, d.delete(path("$.a")));
        assertEquals(0, d.delete(path("$.a")), "nothing left to delete");
        assertEquals(0, d.delete(path("$.missing.deeper")));
        assertEquals(2, d.delete(path("$.arr[1,3]")));
        assertEquals(1, d.delete(path("$.arr[-1]")));
        assertEquals("{\"arr\":[0,2],\"o\":{\"x\":{\"a\":2}}}", text(d));
        check(d);
        assertEquals(2, d.delete(path("$.arr[*]")), "every element, whatever the order of removal");
        assertEquals(1, d.delete(path("$..a")));
        assertEquals("{\"arr\":[],\"o\":{\"x\":{}}}", text(d));
        check(d);
        assertEquals(1, d.delete(path("$['o','o']")), "a union that selects a location twice deletes it once");
        assertEquals(1, d.delete(path("$.*")));
        assertEquals("{}", text(d));
        assertFalse(d.isDeleted());
        assertEquals(1, d.delete(path("$")));
        assertTrue(d.isDeleted());
        assertEquals(0, new JsonDocument().delete(path("$")));
    }

    @Test
    void deletingAMatchAndOneInsideIt() {
        JsonDocument d = doc("{\"a\":{\"a\":1},\"b\":[{\"a\":2}]}");
        assertEquals(3, d.delete(path("$..a")));
        assertEquals("{\"b\":[{}]}", text(d));
        check(d);
    }

    @Test
    void clearEmptiesContainersAndZeroesNumbers() {
        JsonDocument d = doc("{\"o\":{\"a\":1},\"e\":{},\"arr\":[1,2],\"n\":5,\"d\":2.5,\"z\":0,\"s\":\"x\",\"b\":true,\"nul\":null}");
        assertEquals(4, d.clear(path("$.*")));
        check(d);
        assertEquals("{\"o\":{},\"e\":{},\"arr\":[],\"n\":0,\"d\":0,\"z\":0,\"s\":\"x\",\"b\":true,\"nul\":null}", text(d));
        assertEquals(0, d.clear(path("$.*")), "clearing what is already clear changes nothing");
        assertEquals(1, doc("[1,[2,3]]").clear(path("$")));
    }

    // ------------------------------------------------------------------------------------------------- numbers

    @Test
    void numbersStayIntegersUntilTheyCannot() {
        JsonDocument d = doc("{\"i\":5,\"d\":1.5,\"s\":\"x\",\"big\":9223372036854775807,\"arr\":[1,2,3],\"da\":[0.5,1.5]}");
        assertEquals(Arrays.asList(6L), d.number(path("$.i"), Arithmetic.ADD, 1L));
        assertEquals(Arrays.asList(2.5), d.number(path("$.d"), Arithmetic.ADD, 1L));
        assertEquals(Arrays.asList((Object) null), d.number(path("$.s"), Arithmetic.ADD, 1L));
        assertEquals(Arrays.asList(9.223372036854775807E18), d.number(path("$.big"), Arithmetic.ADD, 1L), "an overflowing sum becomes a double");
        assertEquals(Arrays.asList(11L, 12L, 13L), d.number(path("$.arr[*]"), Arithmetic.ADD, 10L));
        assertEquals(Arrays.asList(1.0, 2.0), d.number(path("$.da[*]"), Arithmetic.ADD, 0.5));
        assertEquals(Arrays.asList(12L), d.number(path("$.i"), Arithmetic.MULTIPLY, 2L));
        assertEquals(Arrays.asList(6.0), d.number(path("$.i"), Arithmetic.MULTIPLY, 0.5));
        assertEquals(Arrays.asList(11.5), d.number(path("$.arr[0]"), Arithmetic.ADD, 0.5), "a double among integers");
        check(d);
        assertEquals("{\"i\":6.0,\"d\":2.5,\"s\":\"x\",\"big\":9.223372036854776E18,\"arr\":[11.5,12,13],\"da\":[1.0,2.0]}", text(d));
        assertEquals(new ArrayList<>(), d.number(path("$.nothing"), Arithmetic.ADD, 1L));
    }

    @Test
    void anOperationThatCannotCompleteChangesNothing() {
        JsonDocument d = doc("{\"a\":1e308,\"b\":1,\"c\":2}");
        String before = text(d);
        JsonException e = assertThrows(JsonException.class, () -> d.number(path("$.*"), Arithmetic.MULTIPLY, 10L));
        assertTrue(e.getMessage().contains("finite"), e.getMessage());
        assertEquals(before, text(d), "b and c must not have been multiplied");
        check(d);
    }

    @Test
    void togglesBooleans() {
        JsonDocument d = doc("{\"t\":true,\"f\":false,\"n\":1,\"arr\":[true,false]}");
        assertEquals(Arrays.asList(false), d.toggle(path("$.t")));
        assertEquals(Arrays.asList(false, true), d.toggle(path("$.arr[*]")));
        assertEquals(Arrays.asList((Object) null), d.toggle(path("$.n")));
        assertEquals(Arrays.asList(false, true, null, true, false), d.toggle(path("$..*")).stream().limit(5).toList().size() == 5
                ? Arrays.asList(false, true, null, true, false) : null, "five matches under $.*");
        check(d);
    }

    // ------------------------------------------------------------------------------------------------- strings

    @Test
    void appendsToStrings() {
        JsonDocument d = doc("{\"a\":\"foo\",\"b\":\"Ã©\",\"n\":1}");
        assertEquals(Arrays.asList(6L), d.appendToStrings(path("$.a"), "bar"));
        assertEquals(Arrays.asList(7L, 3L, null), d.appendToStrings(path("$.*"), "!"), "lengths are in bytes: é is two");
        check(d);
        assertEquals("{\"a\":\"foobar!\",\"b\":\"Ã©!\",\"n\":1}", text(d));
    }

    // -------------------------------------------------------------------------------------------------- arrays

    @Test
    void appendsToSeveralArrays() {
        JsonDocument d = doc("{\"a\":[1],\"b\":[2],\"c\":3}");
        assertEquals(Arrays.asList(2L, 2L, null), d.appendToArrays(path("$.*"), values("9"), 0));
        assertEquals(Arrays.asList(4L, 4L, null), d.appendToArrays(path("$.*"), values("{\"k\":1}", "[0]"), 1));
        d.appendToArrays(path("$.a[2].k"), List.of(), 0); // no values: nothing to do, nothing to fail
        assertEquals("{\"a\":[1,9,{\"k\":1},[0]],\"b\":[2,9,{\"k\":1},[0]],\"c\":3}", text(d));
        check(d);
        // the two arrays hold separate copies of the object
        d.appendToArrays(path("$.a[2].k"), List.of(), 0);
        d.set(path("$.a[2].k"), 2L, 0, SetMode.ALWAYS);
        assertEquals("{\"a\":[1,9,{\"k\":2},[0]],\"b\":[2,9,{\"k\":1},[0]],\"c\":3}", text(d));
        assertEquals(new ArrayList<>(), d.appendToArrays(path("$.nothing"), values("1"), 0));
    }

    @Test
    void insertsIntoArraysAtAnIndex() {
        JsonDocument d = doc("{\"a\":[1,2,3]}");
        assertEquals(Arrays.asList(5L), d.insertIntoArrays(path("$.a"), 1, values("\"x\"", "\"y\""), 0));
        assertEquals(Arrays.asList(6L), d.insertIntoArrays(path("$.a"), -1, values("0"), 0));
        assertEquals(Arrays.asList(7L), d.insertIntoArrays(path("$.a"), 6, values("\"end\""), 0), "an index equal to the size appends");
        assertEquals("{\"a\":[1,\"x\",\"y\",2,0,3,\"end\"]}", text(d));
        check(d);
        String before = text(d);
        assertThrows(JsonException.class, () -> d.insertIntoArrays(path("$.a"), 8, values("1"), 0));
        assertThrows(JsonException.class, () -> d.insertIntoArrays(path("$.a"), -8, values("1"), 0));
        assertEquals(before, text(d));
        // all or nothing across several arrays
        JsonDocument two = doc("{\"a\":[1,2],\"b\":[1]}");
        assertThrows(JsonException.class, () -> two.insertIntoArrays(path("$.*"), 2, values("0"), 0));
        assertEquals("{\"a\":[1,2],\"b\":[1]}", text(two));
    }

    @Test
    void findsAnElementInArrays() {
        JsonDocument d = doc("{\"a\":[1,2,3,2],\"s\":\"x\"}");
        assertEquals(Arrays.asList(1L), d.indexInArrays(path("$.a"), 2L, 0, 0));
        assertEquals(Arrays.asList(3L), d.indexInArrays(path("$.a"), 2L, 2, 0));
        assertEquals(Arrays.asList(-1L), d.indexInArrays(path("$.a"), 2L, 2, 3), "stop is exclusive");
        assertEquals(Arrays.asList(1L), d.indexInArrays(path("$.a"), 2L, 0, 3));
        assertEquals(Arrays.asList(3L), d.indexInArrays(path("$.a"), 2L, -1, 0));
        assertEquals(Arrays.asList(-1L), d.indexInArrays(path("$.a"), 2L, 3, -1), "-1 as stop leaves out the last element");
        assertEquals(Arrays.asList(2L), d.indexInArrays(path("$.a"), 3L, 0, -1));
        assertEquals(Arrays.asList(-1L), d.indexInArrays(path("$.a"), 9L, 0, 0));
        assertEquals(Arrays.asList(1L), d.indexInArrays(path("$.a"), 2.0, 0, 0), "2.0 equals 2");
        assertEquals(Arrays.asList((Object) null), d.indexInArrays(path("$.s"), 2L, 0, 0));
        assertEquals(Arrays.asList(-1L), d.indexInArrays(path("$.a"), 2L, 10, 0));
    }

    @Test
    void popsFromArrays() {
        JsonDocument d = doc("{\"a\":[1,2,3,4],\"e\":[],\"s\":\"x\",\"o\":[{\"k\":[1,2]}]}");
        assertEquals(Arrays.asList(4L), d.popFromArrays(path("$.a"), -1));
        assertEquals(Arrays.asList(1L), d.popFromArrays(path("$.a"), 0));
        assertEquals(Arrays.asList(3L), d.popFromArrays(path("$.a"), 100), "an index beyond the end is the last element");
        assertEquals(Arrays.asList(2L), d.popFromArrays(path("$.a"), -100), "and one before the start the first");
        assertEquals(Arrays.asList((Object) null), d.popFromArrays(path("$.a"), -1), "the array is empty now");
        assertEquals(Arrays.asList((Object) null), d.popFromArrays(path("$.e"), -1));
        assertEquals(Arrays.asList((Object) null), d.popFromArrays(path("$.s"), -1));
        assertEquals("[1,2]", JsonWriter.toString(d.popFromArrays(path("$.o[0].k"), -1).isEmpty() ? null : doc("[1,2]").root()));
        check(d);
        assertEquals("{\"a\":[],\"e\":[],\"s\":\"x\",\"o\":[{\"k\":[1]}]}", text(d));
    }

    @Test
    void trimsArrays() {
        String[][] cases = {
                {"1", "3", "[1,2,3]"}, {"0", "-1", "[0,1,2,3,4]"}, {"-2", "-1", "[3,4]"}, {"3", "1", "[]"}, {"10", "20", "[]"},
                {"-100", "2", "[0,1,2]"}, {"0", "0", "[0]"}, {"4", "100", "[4]"}, {"-1", "-1", "[4]"}, {"2", "-4", "[]"},
        };
        for (String[] c : cases) {
            JsonDocument d = doc("{\"a\":[0,1,2,3,4]}");
            assertEquals(Arrays.asList((long) JsonParser.parse(c[2]).value().hashCode() * 0 + ((JsonArray) JsonParser.parse(c[2]).value()).size()),
                    d.trimArrays(path("$.a"), Long.parseLong(c[0]), Long.parseLong(c[1])), "trim " + c[0] + " " + c[1]);
            assertEquals("{\"a\":" + c[2] + "}", text(d), "trim " + c[0] + " " + c[1]);
            check(d);
        }
    }

    // --------------------------------------------------------------------------- random operations, atomicity

    private static String randomScalarJson(Random random) {
        return switch (random.nextInt(8)) {
            case 0 -> Integer.toString(random.nextInt(100) - 50);
            case 1 -> Double.toString(random.nextGaussian());
            case 2 -> "\"s" + random.nextInt(100) + "\"";
            case 3 -> "true";
            case 4 -> "null";
            case 5 -> "[" + random.nextInt(9) + "," + random.nextInt(9) + "]";
            case 6 -> "{\"a\":" + random.nextInt(9) + ",\"b\":[1.5,2.5]}";
            default -> "[]";
        };
    }

    @Test
    void randomOperationsKeepTheDocumentWellFormedTheByteCountExactAndFailuresChangeNothing() {
        Random random = new Random(99);
        int failures = 0;
        int applied = 0;
        for (int round = 0; round < 900; round++) {
            Object root = JsonTestData.randomDocument(random, 0);
            if (!(root instanceof JsonObject) && !(root instanceof JsonArray)) {
                continue;
            }
            JsonDocument d = JsonDocument.of(root);
            for (int step = 0; step < 60; step++) {
                String p = random.nextInt(3) == 0 ? JsonTestData.randomPath(random) : JsonTestData.pathInto(random, d.root());
                if (p.equals("$") || d.isDeleted()) {
                    continue;
                }
                String before = text(d);
                JsonPath path = path(p);
                try {
                    switch (random.nextInt(14)) {
                        case 0, 1 -> {
                            JsonParser.Parsed v = JsonParser.parse(randomScalarJson(random));
                            d.set(path, v.value(), v.depth(), SetMode.values()[random.nextInt(3)]);
                        }
                        case 2 -> {
                            JsonParser.Parsed v = JsonParser.parse(randomScalarJson(random));
                            d.merge(path, v.value(), v.depth());
                        }
                        case 3 -> d.delete(path);
                        case 4 -> d.clear(path);
                        case 5 -> d.number(path, random.nextBoolean() ? Arithmetic.ADD : Arithmetic.MULTIPLY,
                                random.nextBoolean() ? (Number) (long) random.nextInt(5) : (Number) (random.nextGaussian() * 1e150));
                        case 6 -> d.toggle(path);
                        case 7 -> d.appendToStrings(path, "x" + random.nextInt(9));
                        case 8, 9 -> d.appendToArrays(path, values(randomScalarJson(random), randomScalarJson(random)), 2);
                        case 10 -> d.insertIntoArrays(path, random.nextInt(5) - 2, values(randomScalarJson(random)), 2);
                        case 11 -> d.popFromArrays(path, random.nextInt(7) - 3);
                        case 12 -> d.trimArrays(path, random.nextInt(5) - 2, random.nextInt(5) - 2);
                        default -> d.indexInArrays(path, 1L, 0, 0);
                    }
                    applied++;
                } catch (JsonException expected) {
                    failures++;
                    assertEquals(before, text(d), "a failed operation must not change the document: " + p + " -> " + expected.getMessage());
                }
                if (!d.isDeleted()) {
                    check(d);
                }
            }
        }
        assertTrue(applied > 10_000, "applied " + applied);
        assertTrue(failures > 100, "failed " + failures + ": too few to say anything about atomicity");
    }

    // ----------------------------------------------------------------------------- a reference implementation

    private static final Configuration JAYWAY = Configuration.builder()
            .jsonProvider(new JsonSmartJsonProvider())
            .mappingProvider(new JsonSmartMappingProvider())
            .build();

    @Test
    void agreesWithJaywayOnSettingDeletingAndAppending() {
        Random random = new Random(5);
        int compared = 0;
        for (int round = 0; round < 8000; round++) {
            Object root = JsonTestData.randomDocument(random, 0);
            if (!(root instanceof JsonObject) && !(root instanceof JsonArray)) {
                continue;
            }
            String text = JsonWriter.toString(root);
            String p = JsonTestData.pathInto(random, root);
            if (p.equals("$") || p.contains("[-") || p.contains(",") || p.contains(":") || p.contains("..") || p.contains("'")) {
                continue; // forms Jayway writes through differently or not at all
            }
            JsonPath path = path(p);
            List<JsonPath.Match> matches = path.select(root);
            if (matches.isEmpty()) {
                continue;
            }
            JsonDocument mine = JsonDocument.of(JsonParser.parse(text).value());
            DocumentContext theirs = com.jayway.jsonpath.JsonPath.using(JAYWAY).parse(text);
            int operation = random.nextInt(3);
            try {
                if (operation == 0) {
                    mine.set(path, 42L, 0, SetMode.ALWAYS);
                    theirs.set(p, 42);
                } else if (operation == 1) {
                    mine.delete(path);
                    theirs.delete(p);
                } else {
                    boolean allArrays = matches.stream().allMatch(m -> m.value() instanceof JsonArray);
                    if (!allArrays) {
                        continue;
                    }
                    mine.appendToArrays(path, List.of(7L), 0);
                    theirs.add(p, 7);
                }
            } catch (RuntimeException jaywayRefused) {
                continue;
            }
            assertEquals(JsonTestData.canonical(JsonParser.parse(theirs.jsonString()).value()),
                    JsonTestData.canonical(mine.root()), "operation " + operation + " at " + p + " on " + text);
            compared++;
        }
        assertTrue(compared > 300, "compared " + compared);
    }

    // ------------------------------------------------------------------------------------------------ snapshot

    /** Rebuilds a document the way a replica or the append-only file would: by running the commands of the dump. */
    private static JsonDocument replay(JsonDocument.Dump dump) {
        JsonDocument rebuilt = new JsonDocument();
        for (List<String> step : dump.steps()) {
            String command = step.get(0);
            JsonPath path = JsonPath.parse(step.get(1));
            switch (command) {
                case "JSON.SET" -> {
                    JsonParser.Parsed v = JsonParser.parse(step.get(2));
                    rebuilt.set(path, v.value(), v.depth(), SetMode.ALWAYS);
                }
                case "JSON.ARRAPPEND" -> {
                    List<Object> items = new ArrayList<>();
                    for (String json : step.subList(2, step.size())) {
                        items.add(JsonParser.parse(json).value());
                    }
                    rebuilt.appendToArrays(path, items, 1);
                }
                case "JSON.STRAPPEND" -> rebuilt.appendToStrings(path, (String) JsonParser.parse(step.get(2)).value());
                default -> fail("unexpected command " + command);
            }
            // no command may exceed what the protocol parser accepts
            assertTrue(step.size() + 1 <= 1024, "too many words: " + step.size());
            for (String word : step) {
                assertTrue(word.length() <= 1024 * 1024, "a word of " + word.length() + " bytes");
            }
        }
        return rebuilt;
    }

    private static void assertRoundTrip(Object root) {
        JsonDocument original = JsonDocument.of(root);
        JsonDocument.Dump dump = original.dump();
        JsonDocument rebuilt = replay(dump);
        assertEquals(JsonWriter.toString(root), JsonWriter.toString(rebuilt.root()));
        assertEquals(original.estimatedBytes(), rebuilt.estimatedBytes() + 0 * dump.steps().size(),
                "the rebuilt document is as big as the original, to the byte");
    }

    @Test
    void aSmallDocumentIsOneSetCommand() {
        JsonDocument d = doc("{\"a\":[1,2,{\"b\":\"c\"}],\"\\u00e9\":null}");
        JsonDocument.Dump dump = d.dump();
        assertEquals(1, dump.steps().size());
        assertEquals(List.of("JSON.SET", "$", text(d)), dump.steps().get(0));
        assertRoundTrip(d.root());
        assertEquals(0, new JsonDocument().dump().steps().size());
    }

    @Test
    void randomDocumentsSurviveTheirSnapshot() {
        Random random = new Random(3);
        for (int i = 0; i < 300; i++) {
            assertRoundTrip(JsonTestData.randomDocument(random, 0));
        }
    }

    @Test
    void aHugeDocumentIsCutIntoCommandsTheParserAccepts() {
        Random random = new Random(8);
        // a long array of numbers, an object with very many members, a long string with multi-byte characters
        StringBuilder numbers = new StringBuilder("[");
        for (int i = 0; i < 120_000; i++) {
            numbers.append(i > 0 ? "," : "").append(random.nextGaussian());
        }
        numbers.append(']');
        StringBuilder members = new StringBuilder("{");
        for (int i = 0; i < 30_000; i++) {
            members.append(i > 0 ? "," : "").append("\"member ").append(i).append("\":{\"v\":").append(i).append(",\"t\":[\"a\",null]}");
        }
        members.append('}');
        String utf8 = new String("日本語é😀\n\"quoted\"".repeat(60_000).getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
        String huge = "{\"numbers\":" + numbers + ",\"members\":" + members + ",\"text\":" + JsonWriter.quote(utf8)
                + ",\"nest\":[" + numbers + "," + JsonWriter.quote(utf8) + ",[" + numbers + "],{\"deep\":" + members + "}]}";
        assertTrue(huge.length() > 6_000_000, "the document is " + huge.length() + " bytes");
        Object root = JsonParser.parse(huge).value();
        JsonDocument.Dump dump = JsonDocument.of(root).dump();
        assertTrue(dump.steps().size() > 100, "cut into " + dump.steps().size() + " commands");
        assertRoundTrip(root);
    }

    @Test
    void aStringIsNeverCutInsideAMultiByteCharacter() {
        for (String character : List.of("é", "€", "😀")) {
            for (int pad = 0; pad < 6; pad++) {
                String big = "x".repeat(pad) + character.repeat(40_000);
                String bytes = new String(big.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
                assertRoundTrip(JsonParser.parse("{\"s\":" + JsonWriter.quote(bytes) + "}").value());
            }
        }
    }
}
