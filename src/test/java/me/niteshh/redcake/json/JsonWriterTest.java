package me.niteshh.redcake.json;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** The tree -> JSON text step: escaping, number formatting, and the INDENT / NEWLINE / SPACE layout. */
class JsonWriterTest {

    private static Object parse(String text) {
        return JsonParser.parse(text).value();
    }

    @Test
    void escapesExactlyWhatJsonRequires() {
        assertEquals("\"a\\\"b\\\\c\"", JsonWriter.quote("a\"b\\c"));
        assertEquals("\"\\n\\r\\t\\b\\f\"", JsonWriter.quote("\n\r\t\b\f"));
        assertEquals("\"\\u0000\\u0001\\u001f\"", JsonWriter.quote("\u0000\u0001\u001f"));
        assertEquals("\"/ \u007f\"", JsonWriter.quote("/ \u007f"), "the slash and DEL need no escape");
        String utf8 = new String("é€😀".getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
        assertEquals("\"" + utf8 + "\"", JsonWriter.quote(utf8), "UTF-8 bytes are copied, not escaped");
        assertEquals("\"\"", JsonWriter.quote(""));
    }

    @Test
    void numbersDoNotChangeType() {
        assertEquals("1", JsonWriter.toString(1L));
        assertEquals("-9223372036854775808", JsonWriter.toString(Long.MIN_VALUE));
        assertEquals("1.0", JsonWriter.toString(1.0));
        assertEquals("0.1", JsonWriter.toString(0.1));
        assertEquals("-0.0", JsonWriter.toString(-0.0));
        assertEquals("1.0E10", JsonWriter.toString(1e10));
        assertEquals("1.0E-5", JsonWriter.toString(1e-5));
        assertEquals("0.10000000149011612", JsonWriter.toString((double) 0.1f));
        // what it writes is JSON, and reads back as the same double
        for (double d : new double[]{1e10, 1e-5, 123456789.123456789, Double.MAX_VALUE, Double.MIN_VALUE, 0.3}) {
            assertEquals(d, (Double) parse(JsonWriter.toString(d)));
        }
    }

    @Test
    void literalsAndEmptyContainers() {
        assertEquals("null", JsonWriter.toString(Json.NULL));
        assertEquals("true", JsonWriter.toString(Boolean.TRUE));
        assertEquals("false", JsonWriter.toString(Boolean.FALSE));
        assertEquals("{}", JsonWriter.toString(new JsonObject()));
        assertEquals("[]", JsonWriter.toString(new JsonArray()));
    }

    @Test
    void formatsLikeJsonGet() {
        Object doc = parse("{\"a\":1,\"b\":[1,2],\"c\":{},\"d\":[],\"e\":{\"f\":\"g\"}}");
        assertEquals("{\"a\":1,\"b\":[1,2],\"c\":{},\"d\":[],\"e\":{\"f\":\"g\"}}", JsonWriter.toString(doc));
        assertEquals("{\"a\": 1,\"b\": [1,2],\"c\": {},\"d\": [],\"e\": {\"f\": \"g\"}}",
                JsonWriter.toString(doc, "", "", " "), "SPACE alone only follows the colons");
        assertEquals("""
                {
                  "a": 1,
                  "b": [
                    1,
                    2
                  ],
                  "c": {},
                  "d": [],
                  "e": {
                    "f": "g"
                  }
                }""", JsonWriter.toString(doc, "  ", "\n", " "));
        assertEquals("[\n\t1,\n\t[\n\t\t2\n\t]\n]", JsonWriter.toString(parse("[1,[2]]"), "\t", "\n", ""));
    }

    @Test
    void aPackedArrayWritesTheSameAsABoxedOne() {
        JsonArray packed = (JsonArray) parse("[1,2,3]");
        JsonArray boxed = new JsonArray();
        boxed.add(1L);
        boxed.add(2L);
        boxed.add(3L);
        boxed.add("x");
        boxed.remove(3);
        assertEquals(JsonWriter.toString(packed), JsonWriter.toString(boxed));
        assertEquals("[1.5,2.0,-0.25]", JsonWriter.toString(parse("[1.5,2.0,-0.25]")));
    }

    @Test
    void whatItWritesIsAcceptedByAReferenceParser() throws Exception {
        String text = "{\"s\":\"q\\\"\\\\\\n\\u0001\",\"n\":[1,-2.5,1e300,0.1],\"o\":{\"x\":[true,false,null,{}]},\"u\":\"é\"}";
        String written = JsonWriter.toString(parse(new String(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1)));
        String unicode = new String(written.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        Object reference = new net.minidev.json.parser.JSONParser(net.minidev.json.parser.JSONParser.MODE_RFC4627)
                .parse(unicode);
        assertInstanceOf(net.minidev.json.JSONObject.class, reference);
        net.minidev.json.JSONObject object = (net.minidev.json.JSONObject) reference;
        assertEquals("q\"\\\n\u0001", object.get("s"));
        assertEquals("é", object.get("u"));
        assertEquals(4, ((java.util.List<?>) object.get("n")).size());
    }
}
