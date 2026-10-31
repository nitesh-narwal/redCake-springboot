package me.niteshh.redcake.json;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** The JSON text -> tree step: what is accepted, what is refused, and that numbers and bytes survive exactly. */
class JsonParserTest {

    /** A text as the server sees it: UTF-8 bytes, one char per byte. */
    private static String bytes(String unicode) {
        return new String(unicode.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    }

    private static Object parse(String text) {
        return JsonParser.parse(text).value();
    }

    private static String normalized(String text) {
        return JsonWriter.toString(parse(text));
    }

    private static void rejects(String text) {
        assertThrows(JsonException.class, () -> JsonParser.parse(text), () -> "should be rejected: " + text);
    }

    @Test
    void scalars() {
        assertSame(Json.NULL, parse("null"));
        assertEquals(Boolean.TRUE, parse("true"));
        assertEquals(Boolean.FALSE, parse("false"));
        assertEquals(0L, parse("0"));
        assertEquals(0L, parse("-0"));
        assertEquals(123L, parse("123"));
        assertEquals(-45L, parse("-45"));
        assertEquals(1.5, parse("1.5"));
        assertEquals(100.0, parse("1e2"));
        assertEquals(100.0, parse("1E+2"));
        assertEquals(-0.0125, parse("-1.25e-2"));
        assertEquals("abc", parse("\"abc\""));
        assertEquals("", parse("\"\""));
    }

    @Test
    void integersKeepTheirTypeAndLargeOnesBecomeDoubles() {
        assertEquals(Long.MAX_VALUE, parse("9223372036854775807"));
        assertEquals(Long.MIN_VALUE, parse("-9223372036854775808"));
        assertInstanceOf(Double.class, parse("9223372036854775808"));
        assertEquals(1.0E20, parse("100000000000000000000"));
        assertInstanceOf(Long.class, parse("1"));
        assertInstanceOf(Double.class, parse("1.0"));
        assertInstanceOf(Double.class, parse("1e0"));
        assertEquals(-0.0, (Double) parse("-0.0"));
        assertTrue(1 / (Double) parse("-0.0") < 0, "negative zero keeps its sign");
    }

    @Test
    void whitespaceAndStructure() {
        assertEquals("[1,2]", normalized(" \n\t [ 1 , 2 ] \r\n"));
        assertEquals("{\"a\":[1,2,{\"b\":null}],\"c\":\"d\"}", normalized("{ \"a\" : [ 1 , 2 , { \"b\" : null } ] , \"c\" : \"d\" }"));
        assertEquals("{}", normalized("{}"));
        assertEquals("[]", normalized("[ ]"));
        assertEquals("[[]]", normalized("[[]]"));
        assertEquals("{\"a\":{}}", normalized("{\"a\":{}}"));
    }

    @Test
    void membersKeepTheirOrderAndTheLastDuplicateWins() {
        assertEquals("{\"b\":1,\"a\":2,\"c\":3}", normalized("{\"b\":1,\"a\":2,\"c\":3}"));
        assertEquals("{\"a\":2,\"b\":3}", normalized("{\"a\":1,\"b\":3,\"a\":2}"), "a duplicate replaces the value in place");
    }

    @Test
    void stringEscapesBecomeUtf8Bytes() {
        assertEquals("a\nb\t\"\\/", parse("\"a\\nb\\t\\\"\\\\\\/\""));
        assertEquals(bytes("é"), parse("\"\\u00e9\""), "a \\u escape is stored as the UTF-8 bytes of the character");
        assertEquals(bytes("€"), parse("\"\\u20ac\""));
        assertEquals(bytes("😀"), parse("\"\\ud83d\\ude00\""), "a surrogate pair is one four-byte character");
        assertEquals("\u0000", parse("\"\\u0000\""));
        assertEquals(bytes("日本語"), parse("\"" + bytes("日本語") + "\""), "raw UTF-8 passes through untouched");
        assertEquals(bytes("é\n"), parse("\"" + bytes("é") + "\\n\""), "raw bytes and escapes can be mixed");
    }

    @Test
    void refusesWhatIsNotJson() {
        for (String text : List.of(
                "", " ", "{", "}", "[", "]", "[1,]", "[,1]", "[1 2]", "{\"a\":1,}", "{\"a\" 1}", "{\"a\":}", "{a:1}",
                "{\"a\":1 \"b\":2}", "{1:2}", "'a'", "\"abc", "\"a\\x\"", "\"\\u12\"", "\"\\u12g4\"", "\"\\ud800\"",
                "\"\\ud800x\"", "\"\\udc00\"", "\"\\ud800\\u0041\"", "01", "-01", "1.", ".5", "1e", "1e+", "+1", "-", "--1",
                "NaN", "Infinity", "-Infinity", "tru", "nul", "truex", "True", "[1]]", "1 2", "{}{}", "/* c */1", "// c\n1",
                "\"a\tb\"", "\"a\nb\"", "\"a\u0001b\"", "0x10", "1_000", "[1,2", "{\"a\":[}", "\"\\\"")) {
            rejects(text);
        }
    }

    @Test
    void refusesNumbersOutsideTheDoubleRange() {
        rejects("1e400");
        rejects("-1e400");
        assertEquals(0.0, parse("1e-400"), "underflow is zero, not an error");
    }

    @Test
    void refusesInvalidUtf8() {
        for (String raw : List.of(
                "\u00C3",                // a lead byte with nothing after it
                "\u00C3\u0041",          // a lead byte followed by a non-continuation
                "\u0080",                // a stray continuation byte
                "\u00C0\u0080",          // an overlong NUL
                "\u00E0\u0080\u0080",    // an overlong three-byte form
                "\u00ED\u00A0\u0080",    // a UTF-16 surrogate encoded as UTF-8
                "\u00F4\u0090\u0080\u0080", // above U+10FFFF
                "\u00F5\u0080\u0080\u0080", // a lead byte that cannot start anything
                "\u00FF")) {
            rejects("\"" + raw + "\"");
            rejects("{\"" + raw + "\":1}");
            rejects("\"a\\n" + raw + "\"");   // also on the path that decodes escapes
        }
    }

    @Test
    void nestingIsBoundedSoHostileInputCannotOverflowTheStack() {
        String ok = "[".repeat(Json.MAX_DEPTH) + "]".repeat(Json.MAX_DEPTH);
        assertEquals(Json.MAX_DEPTH, JsonParser.parse(ok).depth());
        JsonException tooDeep = assertThrows(JsonException.class,
                () -> JsonParser.parse("[".repeat(Json.MAX_DEPTH + 1) + "]".repeat(Json.MAX_DEPTH + 1)));
        assertTrue(tooDeep.getMessage().contains("deeper"), tooDeep.getMessage());
        rejects("[".repeat(100_000));
        rejects("{\"a\":".repeat(100_000));
    }

    @Test
    void reportsHowDeepAValueNests() {
        assertEquals(0, JsonParser.parse("1").depth());
        assertEquals(1, JsonParser.parse("[]").depth());
        assertEquals(1, JsonParser.parse("{\"a\":1}").depth());
        assertEquals(3, JsonParser.parse("{\"a\":[{\"b\":1},[]]}").depth());
    }

    @Test
    void errorsSayWhereTheProblemIs() {
        JsonException e = assertThrows(JsonException.class, () -> JsonParser.parse("{\"a\":1,\"b\":x}"));
        assertTrue(e.getMessage().contains("offset 11"), e.getMessage()); // the x
        assertTrue(e.getMessage().startsWith("invalid JSON"), e.getMessage());
    }

    @Test
    void numbersThatAreAllIntegersOrAllFractionsAreStoredUnboxed() {
        assertTrue(((JsonArray) parse("[1,2,3]")).isPacked());
        assertTrue(((JsonArray) parse("[0.5,-1.25,3e2]")).isPacked());
        assertFalse(((JsonArray) parse("[1,2.5]")).isPacked(), "an integer must not turn into 1.0");
        assertFalse(((JsonArray) parse("[1.5,2]")).isPacked());
        assertFalse(((JsonArray) parse("[1,\"a\"]")).isPacked());
        assertFalse(((JsonArray) parse("[1,null]")).isPacked());
        assertFalse(((JsonArray) parse("[\"a\",1]")).isPacked());
        assertFalse(((JsonArray) parse("[]")).isPacked());
        assertEquals("[1,2.5,3]", normalized("[1,2.5,3]"));
        assertEquals("[1.5,2,null,\"x\",3.5]", normalized("[1.5,2,null,\"x\",3.5]"));
        assertEquals("[\"x\",1,2,3]", normalized("[\"x\",1,2,3]"));
    }

    @Test
    void aBigNumberArrayRoundTrips() {
        Random random = new Random(7);
        StringBuilder ints = new StringBuilder("[");
        StringBuilder doubles = new StringBuilder("[");
        for (int i = 0; i < 5000; i++) {
            ints.append(i > 0 ? "," : "").append(random.nextLong() >> random.nextInt(63));
            doubles.append(i > 0 ? "," : "").append(random.nextGaussian());
        }
        ints.append(']');
        doubles.append(']');
        assertEquals(ints.toString(), normalized(ints.toString()));
        assertEquals(doubles.toString(), normalized(doubles.toString()));
        assertTrue(((JsonArray) parse(ints.toString())).isPacked());
        assertTrue(((JsonArray) parse(doubles.toString())).isPacked());
    }

    @Test
    void decimalsParseExactlyLikeDoubleParseDouble() {
        Random random = new Random(11);
        for (int i = 0; i < 200_000; i++) {
            StringBuilder text = new StringBuilder();
            if (random.nextInt(3) == 0) {
                text.append('-');
            }
            int integerDigits = random.nextInt(8);
            text.append(integerDigits == 0 ? "0" : Integer.toString(1 + random.nextInt(9)));
            for (int d = 1; d < integerDigits; d++) {
                text.append(random.nextInt(10));
            }
            text.append('.');
            int fractionDigits = 1 + random.nextInt(random.nextInt(4) == 0 ? 25 : 17);
            for (int d = 0; d < fractionDigits; d++) {
                text.append(random.nextInt(10));
            }
            if (random.nextInt(5) == 0) {
                text.append(random.nextBoolean() ? 'e' : 'E').append(random.nextInt(60) - 30);
            }
            String number = text.toString();
            double expected = Double.parseDouble(number);
            if (Double.isInfinite(expected)) {
                continue;
            }
            assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits((Double) parse(number)), number);
        }
    }

    @Test
    void memberNamesAreSharedBetweenDocuments() {
        JsonObject a = (JsonObject) parse("{\"status\":1,\"embedding\":2}");
        JsonObject b = (JsonObject) parse("{\"status\":3,\"embedding\":4}");
        assertSame(a.keyList().get(0), b.keyList().get(0));
        assertSame(a.keyList().get(1), b.keyList().get(1));
        String longName = "n".repeat(60);
        JsonObject c = (JsonObject) parse("{\"" + longName + "\":1}");
        JsonObject d = (JsonObject) parse("{\"" + longName + "\":1}");
        assertNotSame(c.keyList().get(0), d.keyList().get(0), "long names are not pooled");
        assertEquals(c.keyList().get(0), d.keyList().get(0));
    }

    @Test
    void aLargeDocumentParses() {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < 20_000; i++) {
            text.append(i > 0 ? "," : "").append("{\"id\":").append(i).append(",\"tags\":[\"a\",\"b\"],\"v\":[0.1,0.2]}");
        }
        text.append(']');
        JsonArray array = (JsonArray) parse(text.toString());
        assertEquals(20_000, array.size());
        assertEquals(text.toString(), JsonWriter.toString(array));
    }
}
