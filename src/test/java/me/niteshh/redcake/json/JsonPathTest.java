package me.niteshh.redcake.json;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JsonSmartJsonProvider;
import com.jayway.jsonpath.spi.mapper.JsonSmartMappingProvider;
import net.minidev.json.JSONValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Selecting values with a path: the syntax, the semantics of each selector, the limits, and a check against Jayway. */
class JsonPathTest {

    private static final String STORE = """
            {"store":{"book":[
               {"category":"reference","author":"Nigel Rees","title":"Sayings of the Century","price":8.95},
               {"category":"fiction","author":"Evelyn Waugh","title":"Sword of Honour","price":12.99},
               {"category":"fiction","author":"Herman Melville","title":"Moby Dick","isbn":"0-553-21311-3","price":8.99},
               {"category":"fiction","author":"J. R. R. Tolkien","title":"The Lord of the Rings","isbn":"0-395-19395-8","price":22.99}],
             "bicycle":{"color":"red","price":19.95}},
             "expensive":10}""";

    private final Object store = JsonParser.parse(STORE).value();

    private static List<Object> values(List<JsonPath.Match> matches) {
        List<Object> out = new ArrayList<>();
        matches.forEach(m -> out.add(m.value()));
        return out;
    }

    private String select(String path) {
        return select(store, path);
    }

    private static String select(Object document, String path) {
        return JsonWriter.toString(toArray(JsonPath.parse(path).select(document)));
    }

    private static JsonArray toArray(List<JsonPath.Match> matches) {
        JsonArray array = new JsonArray();
        matches.forEach(m -> array.add(m.value()));
        return array;
    }

    private static void invalid(String path) {
        assertThrows(JsonException.class, () -> JsonPath.parse(path), () -> "should be refused: " + path);
    }

    @Test
    void childNamesAndIndexes() {
        assertEquals("[\"red\"]", select("$.store.bicycle.color"));
        assertEquals("[\"red\"]", select("$['store']['bicycle']['color']"));
        assertEquals("[\"red\"]", select("$[\"store\"].bicycle[\"color\"]"));
        assertEquals("[\"Sayings of the Century\"]", select("$.store.book[0].title"));
        assertEquals("[\"The Lord of the Rings\"]", select("$.store.book[-1].title"));
        assertEquals("[10]", select("$.expensive"));
        assertEquals("[]", select("$.nothing"));
        assertEquals("[]", select("$.store.book[10]"));
        assertEquals("[]", select("$.store.book[-10]"));
        assertEquals("[]", select("$.expensive.deeper"), "a name on a number selects nothing");
        assertEquals("[]", select("$.store.book.title"), "a name on an array selects nothing");
        assertEquals("[]", select("$.expensive[0]"), "an index on a number selects nothing");
        assertEquals(1, JsonPath.parse("$").select(store).size());
        assertSame(store, JsonPath.parse("$").select(store).get(0).value());
    }

    @Test
    void wildcardsUnionsAndSlices() {
        assertEquals("[\"Nigel Rees\",\"Evelyn Waugh\",\"Herman Melville\",\"J. R. R. Tolkien\"]", select("$.store.book[*].author"));
        assertEquals(2, JsonPath.parse("$.store.*").select(store).size());
        assertEquals(2, JsonPath.parse("$.store[*]").select(store).size());
        assertEquals("[]", select("$.expensive.*"), "a wildcard on a scalar selects nothing");
        assertEquals("[\"red\",19.95]", select("$.store.bicycle.*"), "object members in insertion order");
        assertEquals("[\"Sword of Honour\",\"Moby Dick\"]", select("$.store.book[1:3].title"));
        assertEquals("[\"Sayings of the Century\",\"Sword of Honour\"]", select("$.store.book[:2].title"));
        assertEquals("[\"The Lord of the Rings\"]", select("$.store.book[-1:].title"));
        assertEquals("[\"Sword of Honour\",\"The Lord of the Rings\"]", select("$.store.book[1::2].title"));
        assertEquals("[\"The Lord of the Rings\",\"Sword of Honour\"]", select("$.store.book[::-2].title"));
        assertEquals("[\"The Lord of the Rings\",\"Moby Dick\",\"Sword of Honour\",\"Sayings of the Century\"]", select("$.store.book[::-1].title"));
        assertEquals("[]", select("$.store.book[2:2].title"));
        assertEquals("[]", select("$.store.book[::0].title"), "a zero step selects nothing");
        assertEquals("[\"Sayings of the Century\",\"Moby Dick\"]", select("$.store.book[0,2].title"));
        assertEquals("[\"red\",19.95]", select("$.store.bicycle['color','price']"));
        assertEquals("[\"red\"]", select("$.store.bicycle['color','nothing']"));
        assertEquals("[\"red\",\"red\"]", select("$.store.bicycle['color','color']"), "a union may select a value twice");
    }

    @Test
    void recursiveDescentFindsEveryDepthInDocumentOrder() {
        assertEquals("[8.95,12.99,8.99,22.99,19.95]", select("$..price"));
        assertEquals("[\"Nigel Rees\",\"Evelyn Waugh\",\"Herman Melville\",\"J. R. R. Tolkien\"]", select("$..author"));
        assertEquals("[\"Moby Dick\"]", select("$..book[2].title"));
        assertEquals("[\"Moby Dick\"]", select("$..[2].title"));
        assertEquals("[\"The Lord of the Rings\"]", select("$..book[-1].title"));
        assertEquals("[\"red\"]", select("$..color"));
        Object small = JsonParser.parse("{\"a\":[1,2,{\"b\":3}],\"c\":4}").value();
        assertEquals(6, JsonPath.parse("$..*").select(small).size(), "a, c, 1, 2, {b}, 3 - the root is not among its descendants");
        assertEquals("[{\"a\":1},1,2]", select(JsonParser.parse("{\"a\":{\"a\":1},\"b\":[{\"a\":2}]}").value(), "$..a"),
                "an a below another a is found too, after it");
    }

    @Test
    void filtersCompareAndCombine() {
        assertEquals("[\"Sayings of the Century\",\"Moby Dick\"]", select("$..book[?(@.price<10)].title"));
        assertEquals("[\"Sayings of the Century\",\"Moby Dick\"]", select("$..book[?(@.price < 10)].title"));
        assertEquals("[\"Moby Dick\",\"The Lord of the Rings\"]", select("$..book[?(@.isbn)].title"));
        assertEquals("[\"Sayings of the Century\",\"Sword of Honour\"]", select("$..book[?(!@.isbn)].title"));
        assertEquals("[\"Sword of Honour\",\"The Lord of the Rings\"]", select("$..book[?(@.category=='fiction' && @.price>10)].title"));
        assertEquals("[\"Sayings of the Century\",\"Moby Dick\",\"The Lord of the Rings\"]",
                select("$..book[?(@.price<9 || @.price>20)].title"));
        assertEquals("[\"Sword of Honour\",\"The Lord of the Rings\"]", select("$.store.book[?(@.price > $.expensive)].title"),
                "$ inside a filter is the document root");
        assertEquals("[\"Sayings of the Century\"]", select("$..book[?(@.category==\"reference\")].title"));
        assertEquals("[\"Sword of Honour\"]", select("$..book[?(@.price==12.99)].title"));
        assertEquals("[\"Sayings of the Century\",\"Sword of Honour\",\"Moby Dick\"]", select("$..book[?(@.title != \"The Lord of the Rings\")].title"));
        assertEquals("[\"Moby Dick\"]", select("$..book[?(@.author >= 'Herman' && @.author < 'I')].title"));
        assertEquals("[\"Sayings of the Century\",\"Sword of Honour\",\"Moby Dick\"]", select("$..book[?((@.price<10 && @.title<'T') || @.price==12.99)].title"));
        assertEquals("[8.95,8.99]", select("$..book[?(@.price<=8.99)].price"));
        assertEquals("[12.99,22.99]", select("$..book[?(@.price>=12.99)].price"));
        assertEquals("[]", select("$..book[?(@.price<0)]"));
        assertEquals("[1,2]", select(JsonParser.parse("[1,2,\"x\",null,true,3]").value(), "$[?(@ < 3)]"), "@ alone is the candidate; only numbers compare");
        assertEquals("[null]", select(JsonParser.parse("[1,null,2]").value(), "$[?(@ == null)]"));
        assertEquals("[true]", select(JsonParser.parse("[true,false,1]").value(), "$[?(@ == true)]"));
        assertEquals("[]", select(JsonParser.parse("[\"1\",true]").value(), "$[?(@ == 1)]"), "types are not coerced");
        assertEquals("[{\"a\":1}]", select(JsonParser.parse("[{\"a\":1},{\"b\":2}]").value(), "$[?(@.a==1)]"));
        // a filter works on the members of an object as well
        assertEquals("[{\"v\":2}]", select(JsonParser.parse("{\"x\":{\"v\":1},\"y\":{\"v\":2}}").value(), "$[?(@.v>1)]"));
        // missing operands: != is the negation of ==, ordering with nothing is false
        assertEquals("[{\"b\":2}]", select(JsonParser.parse("[{\"a\":1},{\"b\":2}]").value(), "$[?(@.a!=1)]"));
        assertEquals("[{\"b\":2}]", select(JsonParser.parse("[{\"a\":1},{\"b\":2}]").value(), "$[?(@.a==@.c)]"), "both missing are equal");
        assertEquals("[]", select(JsonParser.parse("[{\"a\":1},{\"b\":2}]").value(), "$[?(@.a<5 && @.b<5)]"));
        // the int/double distinction does not matter for comparisons
        assertEquals("[{\"a\":1}]", select(JsonParser.parse("[{\"a\":1},{\"a\":2.5}]").value(), "$[?(@.a==1.0)]"));
    }

    @Test
    void nameSyntaxAndEscapes() {
        Object document = JsonParser.parse("{\"a.b\":1,\"a b\":2,\"it's\":3,\"x\\\"y\":4,\"$\":5,\"\":6,\"-\":8}").value();
        assertEquals("[1]", select(document, "$['a.b']"));
        assertEquals("[2]", select(document, "$[\"a b\"]"));
        assertEquals("[3]", select(document, "$['it\\'s']"));
        assertEquals("[3]", select(document, "$[\"it's\"]"));
        assertEquals("[4]", select(document, "$['x\"y']"));
        assertEquals("[4]", select(document, "$['x\\\"y']"));
        assertEquals("[5]", select(document, "$['$']"));
        assertEquals("[6]", select(document, "$['']"));
        assertEquals("[8]", select(document, "$.-"));
        String utf8 = new String("é".getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.ISO_8859_1);
        Object utf8Document = JsonParser.parse("{\"" + utf8 + "\":7}").value();
        assertEquals("[7]", select(utf8Document, "$['\\u00e9']"), "an escape in a path matches the UTF-8 name");
        assertEquals("[7]", select(utf8Document, "$['" + utf8 + "']"));
        assertEquals("[7]", select(utf8Document, "$." + utf8));
    }

    @Test
    void whereAMatchSitsIsReported() {
        List<JsonPath.Match> book = JsonPath.parse("$.store.book[1]").select(store);
        assertEquals(1, book.size());
        JsonPath.Match match = book.get(0);
        assertEquals(1, match.key());
        assertInstanceOf(JsonArray.class, match.parent());
        assertEquals(3, match.depth());
        assertFalse(match.isRoot());
        JsonPath.Match root = JsonPath.parse("$").select(store).get(0);
        assertTrue(root.isRoot());
        assertEquals(0, root.depth());
        JsonPath.Match name = JsonPath.parse("$.store.bicycle.color").select(store).get(0);
        assertEquals("color", name.key());
        assertEquals(3, name.depth());
        assertInstanceOf(JsonObject.class, name.parent());
        assertEquals(1, JsonPath.parse("$.expensive").select(store).get(0).depth());
    }

    @Test
    void theLegacyDialectAcceptsOnlyDefinitePaths() {
        assertTrue(JsonPath.parse(".").isLegacy());
        assertTrue(JsonPath.parse(".").isRoot());
        assertFalse(JsonPath.parse("$").isLegacy());
        assertTrue(JsonPath.parse("$").isRoot());
        assertEquals("[\"red\"]", select(".store.bicycle.color"));
        assertEquals("[\"red\"]", select("store.bicycle.color"));
        assertEquals("[\"Sword of Honour\"]", select(".store.book[1].title"));
        assertEquals("[\"Sword of Honour\"]", select("store.book[1].title"));
        assertEquals("[\"red\"]", select("['store']['bicycle']['color']"));
        assertEquals("[\"red\"]", select(".store['bicycle'].color"));
        assertEquals("[10]", select("expensive"));
        assertEquals(1, JsonPath.parse(".").select(store).size());
        for (String path : List.of(".store.*", ".store.book[*]", ".store..price", ".store.book[0,1]", ".store.book[0:2]",
                ".store.book[?(@.price<10)]", "store.*", "..price")) {
            invalid(path);
        }
        assertTrue(JsonPath.parse("$.a.b[0]").isDefinite());
        assertFalse(JsonPath.parse("$.a.*").isDefinite());
        assertFalse(JsonPath.parse("$..a").isDefinite());
        assertFalse(JsonPath.parse("$.a[0,1]").isDefinite());
    }

    @Test
    void refusesInvalidPaths() {
        for (String path : List.of(
                "", "$.", "$..", "$[", "$[1", "$['a", "$['a'", "$[]", "$[,1]", "$[1,]", "$.a b", "$.a[?(@.b ==)]", "$[?(@.a > )]",
                "$[?(", "$[?(@.a", "$[?(@.a ==== 1)]", "$[?(@.a && )]", "$[?(1)]", "$[?(\"x\")]", "$[?(@..a == 1)]",
                "$[?(@.a[*] == 1)]", "$[?(@.a == 1 @.b)]", "$['\\x']", "$['\\u12']", "$['\\ud800']", "$[a]", "$.a.[0]",
                "$[1:2:3:4]", "$['a'.'b']", "$[?(@.a == 1e)]", "$['a\\")) {
            invalid(path);
        }
        invalid("$." + "a".repeat(JsonPath.MAX_LENGTH));
        invalid("$[?" + "(".repeat(40) + "@.a" + ")".repeat(40) + "]");
    }

    @Test
    void anExpensivePathIsAbandonedInsteadOfRunningForever() {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < 3000; i++) {
            text.append(i > 0 ? "," : "").append("[1,[2,[3,[4,[5]]]]]");
        }
        Object document = JsonParser.parse(text.append(']').toString()).value();
        Object deep = JsonParser.parse("[".repeat(100) + "1" + "]".repeat(100)).value();
        JsonException e = assertThrows(JsonException.class,
                () -> JsonPath.parse("$..*..*..*..*..*..*").select(deep));
        assertTrue(e.getMessage().contains("too expensive"), e.getMessage());
        // one level is fine and fast
        assertTrue(JsonPath.parse("$..*").select(document).size() > 3000);
        // nested filters share the one budget, so they cannot multiply it
        assertThrows(JsonException.class, () -> JsonPath.parse("$..[?($..[?($..*)])]").select(document));
    }

    @Test
    void aDeepDocumentIsFine() {
        Object document = JsonParser.parse("[".repeat(100) + "1" + "]".repeat(100)).value();
        assertEquals(1, JsonPath.parse("$" + "[0]".repeat(100)).select(document).size());
        assertEquals(1, JsonPath.parse("$..*").select(document).size() - 99);
    }

    // ----------------------------------------------------------------------- a reference implementation

    private static final Configuration JAYWAY = Configuration.builder()
            .jsonProvider(new JsonSmartJsonProvider())
            .mappingProvider(new JsonSmartMappingProvider())
            .options(Option.ALWAYS_RETURN_LIST, Option.SUPPRESS_EXCEPTIONS)
            .build();

    @Test
    void agreesWithJaywayOnTheSharedSubset() {
        Random random = new Random(2024);
        int compared = 0;
        int nonEmpty = 0;
        for (int d = 0; d < 400; d++) {
            Object document = JsonTestData.randomDocument(random, 0);
            String text = JsonWriter.toString(document);
            for (int p = 0; p < 40; p++) {
                String path = p % 2 == 0 ? JsonTestData.randomPath(random) : JsonTestData.pathInto(random, document);
                // a root that is a scalar makes Jayway read the path as a different dialect for some forms; skip those
                if (!(document instanceof JsonObject) && !(document instanceof JsonArray)) {
                    continue;
                }
                List<String> mine = new ArrayList<>();
                for (JsonPath.Match match : JsonPath.parse(path).select(document)) {
                    mine.add(JsonTestData.canonical(match.value()));
                }
                List<String> theirs = new ArrayList<>();
                Object read = com.jayway.jsonpath.JsonPath.using(JAYWAY).parse(text).read(path);
                for (Object value : (List<?>) read) {
                    theirs.add(JsonTestData.canonical(JsonParser.parse(JSONValue.toJSONString(value)).value()));
                }
                // the two order the results of ".." and of wildcards over objects differently, so compare as multisets
                Collections.sort(mine);
                Collections.sort(theirs);
                assertEquals(theirs, mine, "path " + path + " on " + text);
                compared++;
                nonEmpty += mine.isEmpty() ? 0 : 1;
            }
        }
        assertTrue(compared > 5_000, "compared " + compared);
        assertTrue(nonEmpty > 3_000, "only " + nonEmpty + " paths selected anything, the test would prove little");
    }
}
