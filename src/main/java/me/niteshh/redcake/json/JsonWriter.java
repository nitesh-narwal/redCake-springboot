package me.niteshh.redcake.json;

import java.util.List;

/**
 * Serializes a value to JSON text, compactly or formatted like {@code JSON.GET ... INDENT ... NEWLINE ... SPACE ...}.
 *
 * <p>Strings are byte strings holding UTF-8, so only what JSON requires is escaped (the quote, the backslash and the
 * control characters) and every other byte - including the bytes of multi-byte characters - is copied as it is. A
 * packed number array is written straight from its {@code long[]} / {@code double[]}, with nothing boxed. Doubles use
 * the shortest text that reads back as the same double ({@link Double#toString}); integral doubles keep their
 * {@code .0} so a number does not change type on its way out.
 */
public final class JsonWriter implements JsonObject.Visitor {

    private final StringBuilder out;
    private final String indent;
    private final String newline;
    private final String space;
    private final boolean pretty;
    private int level;
    /** Whether the member about to be written is the first of its object. */
    private boolean first;

    /** Compact output into {@code out}. */
    public JsonWriter(StringBuilder out) {
        this(out, "", "", "");
    }

    /**
     * @param indent  written once per nesting level at the start of every element
     * @param newline written before every element and before a closing bracket
     * @param space   written after the colon of a member
     */
    public JsonWriter(StringBuilder out, String indent, String newline, String space) {
        this.out = out;
        this.indent = indent;
        this.newline = newline;
        this.space = space;
        this.pretty = !indent.isEmpty() || !newline.isEmpty();
    }

    /** @return the compact JSON text of {@code node} */
    public static String toString(Object node) {
        StringBuilder sb = new StringBuilder(64);
        new JsonWriter(sb).write(node);
        return sb.toString();
    }

    /** @return the JSON text of {@code node} formatted with the given strings (all empty: compact) */
    public static String toString(Object node, String indent, String newline, String space) {
        StringBuilder sb = new StringBuilder(64);
        new JsonWriter(sb, indent, newline, space).write(node);
        return sb.toString();
    }

    /** @return {@code text} as a JSON string literal, quotes included */
    public static String quote(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 2);
        writeString(sb, text);
        return sb.toString();
    }

    /**
     * Writes a value. Besides the nodes of a document it takes a {@link List} (written as an array, and the matches
     * of a path are exactly that) and Java {@code null} (written as JSON {@code null}: the entry of a match that a
     * command could not apply to).
     */
    public void write(Object node) {
        switch (node) {
            case null -> out.append("null");
            case List<?> list -> writeList(list);
            case String s -> writeString(out, s);
            case Long l -> out.append(l.longValue());
            case Double d -> out.append(d.doubleValue());
            case Boolean b -> out.append(b ? "true" : "false");
            case JsonObject o -> writeObject(o);
            case JsonArray a -> writeArray(a);
            default -> out.append("null");
        }
    }

    private void writeObject(JsonObject object) {
        if (object.isEmpty()) {
            out.append("{}");
            return;
        }
        out.append('{');
        boolean outerFirst = first;
        first = true;
        level++;
        object.forEach(this);
        level--;
        first = outerFirst;
        lineBreak();
        out.append('}');
    }

    /** One member of the object being written. */
    @Override
    public void visit(String key, Object value) {
        if (!first) {
            out.append(',');
        }
        first = false;
        lineBreak();
        writeString(out, key);
        out.append(':').append(space);
        write(value);
    }

    private void writeList(List<?> items) {
        if (items.isEmpty()) {
            out.append("[]");
            return;
        }
        out.append('[');
        level++;
        boolean firstItem = true;
        for (Object item : items) {
            if (!firstItem) {
                out.append(',');
            }
            firstItem = false;
            lineBreak();
            write(item);
        }
        level--;
        lineBreak();
        out.append(']');
    }

    /** Writes an object with the given member names and values (a value may be a list, see {@link #write}). */
    public void writeMembers(List<String> names, List<?> values) {
        if (names.isEmpty()) {
            out.append("{}");
            return;
        }
        out.append('{');
        boolean outerFirst = first;
        first = true;
        level++;
        for (int i = 0; i < names.size(); i++) {
            visit(names.get(i), values.get(i));
        }
        level--;
        first = outerFirst;
        lineBreak();
        out.append('}');
    }

    private void writeArray(JsonArray array) {
        int n = array.size();
        if (n == 0) {
            out.append("[]");
            return;
        }
        out.append('[');
        level++;
        int mode = array.mode();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                out.append(',');
            }
            lineBreak();
            switch (mode) {
                case JsonArray.LONGS -> out.append(array.longAt(i));
                case JsonArray.DOUBLES -> out.append(array.doubleAt(i));
                default -> write(array.objectAt(i));
            }
        }
        level--;
        lineBreak();
        out.append(']');
    }

    /** A line break followed by the indentation of the current level - nothing at all when writing compactly. */
    private void lineBreak() {
        if (pretty) {
            out.append(newline);
            for (int i = 0; i < level; i++) {
                out.append(indent);
            }
        }
    }

    static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        int start = 0;
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c != '"' && c != '\\') {
                continue;
            }
            sb.append(s, start, i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    sb.append("\\u00");
                    sb.append(Character.forDigit(c >> 4, 16));
                    sb.append(Character.forDigit(c & 0xF, 16));
                }
            }
            start = i + 1;
        }
        sb.append(s, start, n);
        sb.append('"');
    }
}
