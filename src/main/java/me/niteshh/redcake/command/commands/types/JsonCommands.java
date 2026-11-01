package me.niteshh.redcake.command.commands.types;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandException;
import me.niteshh.redcake.command.CommandProvider;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.PreparedWork;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.json.Json;
import me.niteshh.redcake.json.JsonDocument;
import me.niteshh.redcake.json.JsonDocument.Arithmetic;
import me.niteshh.redcake.json.JsonDocument.SetMode;
import me.niteshh.redcake.json.JsonArray;
import me.niteshh.redcake.json.JsonException;
import me.niteshh.redcake.json.JsonObject;
import me.niteshh.redcake.json.JsonParser;
import me.niteshh.redcake.json.JsonPath;
import me.niteshh.redcake.json.JsonWriter;
import me.niteshh.redcake.resp.ArrayValue;
import me.niteshh.redcake.resp.BulkString;
import me.niteshh.redcake.resp.ErrorValue;
import me.niteshh.redcake.resp.IntegerValue;
import me.niteshh.redcake.resp.NullValue;
import me.niteshh.redcake.resp.RespValue;
import me.niteshh.redcake.resp.SimpleString;
import me.niteshh.redcake.store.KeyValueStore;
import me.niteshh.redcake.store.ValueType;
import me.niteshh.redcake.store.WrongTypeException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import static me.niteshh.redcake.command.LambdaCommand.read;
import static me.niteshh.redcake.command.LambdaCommand.shrink;
import static me.niteshh.redcake.command.LambdaCommand.write;

/**
 * JSON documents, with the command set of RedisJSON: {@code JSON.SET GET MGET DEL FORGET TYPE NUMINCRBY NUMMULTBY
 * STRAPPEND STRLEN ARRAPPEND ARRINSERT ARRINDEX ARRLEN ARRPOP ARRTRIM OBJKEYS OBJLEN TOGGLE CLEAR MERGE DEBUG}, so
 * that the client libraries that speak it work unchanged (checked with {@code redis-py}'s {@code r.json()}, created
 * for RESP2 - RedCake does not speak RESP3). A record with text, an embedding, metadata and a status is one key;
 * {@code JSON.SET doc:1 $.status '"done"'}
 * changes one field of it, atomically, and replicates and logs exactly that.
 *
 * <p>The semantics were written from RedisJSON's documentation and <b>not checked against a Redis Stack server</b>
 * (none was available): {@code JSON.SET key $ ...} replies, the two path dialects (a JSONPath starting with {@code $}
 * answers with arrays, one entry per match; a legacy path answers with the single value and fails when there is none),
 * {@code JSON.GET}'s {@code INDENT / NEWLINE / SPACE}, the error texts. Not implemented: {@code JSON.MSET},
 * {@code JSON.RESP}, {@code JSON.NUMPOWBY}.
 *
 * <p>The commands only parse arguments and format replies; every operation on a document is in {@link JsonDocument}.
 * A command whose argument is JSON text parses it <em>before</em> the primary write lock is taken
 * ({@link RedCakeCommand#prepare}, on the client's own thread): decoding a 100 KB record is the expensive part of
 * storing it, and then connections do it in parallel instead of queueing behind each other.
 */
@Component
@RequiredArgsConstructor
public class JsonCommands implements CommandProvider {

    /** The path used when a command leaves it out: the root, in the legacy dialect, as RedisJSON does. */
    private static final String ROOT = ".";

    private final KeyValueStore store;

    @Override
    public List<RedCakeCommand> commands() {
        return List.of(
                write("JSON.SET", 3, 4, guarded(this::set)).preparedBy(c -> prepareValues(c, 3, 4)),
                read("JSON.GET", 1, -1, guarded(this::get)),
                read("JSON.MGET", 2, -1, guarded(this::mget)),
                shrink("JSON.DEL", 1, 2, guarded(this::del)),
                shrink("JSON.FORGET", 1, 2, guarded(this::del)),
                read("JSON.TYPE", 1, 2, guarded(this::type)),
                write("JSON.NUMINCRBY", 3, 3, guarded(a -> number(a, Arithmetic.ADD))),
                write("JSON.NUMMULTBY", 3, 3, guarded(a -> number(a, Arithmetic.MULTIPLY))),
                write("JSON.STRAPPEND", 2, 3, guarded(this::strAppend)).preparedBy(c -> prepareValues(c, Math.max(c.size() - 1, 2), -1)),
                read("JSON.STRLEN", 1, 2, guarded(this::strLen)),
                write("JSON.ARRAPPEND", 3, -1, guarded(this::arrAppend)).preparedBy(c -> prepareValues(c, 3, -1)),
                write("JSON.ARRINSERT", 4, -1, guarded(this::arrInsert)).preparedBy(c -> prepareValues(c, 4, -1)),
                read("JSON.ARRINDEX", 3, 5, guarded(this::arrIndex)),
                read("JSON.ARRLEN", 1, 2, guarded(this::arrLen)),
                shrink("JSON.ARRPOP", 1, 3, guarded(this::arrPop)),
                shrink("JSON.ARRTRIM", 4, 4, guarded(this::arrTrim)),
                read("JSON.OBJKEYS", 1, 2, guarded(this::objKeys)),
                read("JSON.OBJLEN", 1, 2, guarded(this::objLen)),
                shrink("JSON.TOGGLE", 2, 2, guarded(this::toggle)),
                shrink("JSON.CLEAR", 1, 2, guarded(this::clear)),
                write("JSON.MERGE", 3, 3, guarded(this::merge)).preparedBy(c -> prepareValues(c, 3, 4)),
                read("JSON.DEBUG", 2, 3, guarded(this::debug))
        );
    }

    // plumbing

    /** A problem with the JSON text or the path is an error reply, not a crash. */
    private static Function<List<String>, RespValue> guarded(Function<List<String>, RespValue> body) {
        return arguments -> {
            try {
                return body.apply(arguments);
            } catch (JsonException e) {
                return new ErrorValue(e.getMessage());
            }
        };
    }

    /** Runs {@code fn} on the document of {@code key}, or on {@code null} if there is none. */
    private <T> T withDocument(String key, Function<JsonDocument, T> fn) {
        return store.read(key, ValueType.JSON, o -> fn.apply((JsonDocument) o));
    }

    /** Changes the document of {@code key}, which must exist. */
    private <T> T updateDocument(String key, Function<JsonDocument, T> fn) {
        boolean[] ran = {false};
        T result = store.mutate(key, ValueType.JSON, null, o -> {
            ran[0] = true;
            return fn.apply((JsonDocument) o);
        });
        if (!ran[0]) {
            throw new CommandException("could not perform this operation on a key that doesn't exist");
        }
        return result;
    }

    private static JsonPath pathOf(List<String> a, int index) {
        return JsonPath.parse(index < a.size() ? a.get(index) : ROOT);
    }

    /** The JSON values of a command, parsed before the write lock (see {@link #prepare}). */
    private record PreparedValues(String[] texts, JsonParser.Parsed[] parsed) {

        /** The tree parsed for {@code text}, handed out once so that two equal arguments never share one tree. */
        JsonParser.Parsed take(String text) {
            for (int i = 0; i < texts.length; i++) {
                if (parsed[i] != null && (texts[i] == text || texts[i].equals(text))) {
                    JsonParser.Parsed found = parsed[i];
                    parsed[i] = null;
                    return found;
                }
            }
            return null;
        }
    }

    /**
     * Parses the arguments {@code from} (inclusive) to {@code to} (exclusive, -1 for the end) of {@code command} as
     * JSON. An argument that is not valid is simply left for the command to report.
     */
    private static Object prepareValues(List<String> command, int from, int to) {
        int end = to < 0 ? command.size() : Math.min(to, command.size());
        if (from >= end) {
            return null;
        }
        String[] texts = new String[end - from];
        JsonParser.Parsed[] parsed = new JsonParser.Parsed[end - from];
        boolean any = false;
        for (int i = 0; i < texts.length; i++) {
            texts[i] = command.get(from + i);
            try {
                parsed[i] = JsonParser.parse(texts[i]);
                any = true;
            } catch (JsonException invalid) {
                // reported by the command itself
            }
        }
        return any ? new PreparedValues(texts, parsed) : null;
    }

    private static JsonParser.Parsed valueOf(PreparedValues prepared, String text) {
        JsonParser.Parsed parsed = prepared == null ? null : prepared.take(text);
        return parsed != null ? parsed : JsonParser.parse(text);
    }

    /** What a command found out per match, and what the first match was if it could not be used. */
    private record Outcome(List<Object> values, String foundType) {
    }

    private static Outcome outcome(JsonDocument doc, JsonPath path, List<Object> values) {
        String found = null;
        if (path.isLegacy() && !values.isEmpty() && values.get(0) == null) {
            List<JsonPath.Match> matches = doc.select(path);
            found = matches.isEmpty() ? null : Json.typeName(matches.get(0).value());
        }
        return new Outcome(values, found);
    }

    /**
     * The reply of a command that answers per match: for a JSONPath an array with one entry per match ({@code nil}
     * where the command did not apply), for a legacy path the one value - or an error if there is no such value.
     */
    private static RespValue perMatch(JsonPath path, Outcome outcome, String expected, Function<Object, RespValue> element) {
        if (!path.isLegacy()) {
            List<RespValue> items = new ArrayList<>(outcome.values().size());
            for (Object value : outcome.values()) {
                items.add(value == null ? new NullValue() : element.apply(value));
            }
            return new ArrayValue(items);
        }
        if (outcome.values().isEmpty()) {
            throw pathMissing(path);
        }
        Object value = outcome.values().get(0);
        if (value == null) {
            throw new JsonException("wrong type of path value - expected " + expected + " but found " + outcome.foundType());
        }
        return element.apply(value);
    }

    private static RespValue integer(Object value) {
        return new IntegerValue((Long) value);
    }

    private static RespValue ok() {
        return new SimpleString("OK");
    }

    private static JsonException pathMissing(JsonPath path) {
        return new JsonException("Path '" + path.text() + "' does not exist");
    }

    // set / get / del

    /** {@code JSON.SET key path value [NX | XX]} */
    private RespValue set(List<String> a) {
        String key = a.get(0);
        JsonPath path = JsonPath.parse(a.get(1));
        SetMode mode = SetMode.ALWAYS;
        if (a.size() == 4) {
            mode = switch (a.get(3).toUpperCase(Locale.ROOT)) {
                case "NX" -> SetMode.ONLY_IF_ABSENT;
                case "XX" -> SetMode.ONLY_IF_PRESENT;
                default -> throw new CommandException(CommandSupport.SYNTAX_ERROR);
            };
        }
        JsonParser.Parsed value = valueOf(PreparedWork.take(PreparedValues.class), a.get(2));
        SetMode setMode = mode;
        boolean[] ran = {false};
        // only a set of the whole document may create the key
        Boolean applied = store.<Boolean>mutate(key, ValueType.JSON,
                path.isRoot() && mode != SetMode.ONLY_IF_PRESENT ? JsonDocument::new : null,
                o -> {
                    ran[0] = true;
                    return ((JsonDocument) o).set(path, value.value(), value.depth(), setMode);
                });
        if (!ran[0]) {
            if (mode == SetMode.ONLY_IF_PRESENT) {
                return new NullValue();
            }
            throw new CommandException("new objects must be created at the root");
        }
        return Boolean.TRUE.equals(applied) ? ok() : new NullValue();
    }

    /** {@code JSON.GET key [INDENT s] [NEWLINE s] [SPACE s] [path ...]} */
    private RespValue get(List<String> a) {
        String indent = "";
        String newline = "";
        String space = "";
        List<String> paths = new ArrayList<>();
        for (int i = 1; i < a.size(); i++) {
            switch (a.get(i).toUpperCase(Locale.ROOT)) {
                case "INDENT" -> indent = optionValue(a, ++i);
                case "NEWLINE" -> newline = optionValue(a, ++i);
                case "SPACE" -> space = optionValue(a, ++i);
                case "NOESCAPE" -> {
                    // accepted and ignored, as RedisJSON does
                }
                default -> paths.add(a.get(i));
            }
        }
        if (paths.isEmpty()) {
            paths.add(ROOT);
        }
        List<JsonPath> parsed = new ArrayList<>();
        for (String path : paths) {
            parsed.add(JsonPath.parse(path));
        }
        String indentation = indent;
        String lineBreak = newline;
        String spacing = space;
        return withDocument(a.get(0), doc -> {
            if (doc == null) {
                return new NullValue();
            }
            StringBuilder out = new StringBuilder();
            JsonWriter writer = new JsonWriter(out, indentation, lineBreak, spacing);
            if (parsed.size() == 1) {
                writer.write(selected(doc, parsed.get(0)));
            } else {
                List<Object> results = new ArrayList<>();
                for (JsonPath path : parsed) {
                    results.add(selected(doc, path));
                }
                writer.writeMembers(paths, results);
            }
            return new BulkString(out.toString());
        });
    }

    private static String optionValue(List<String> a, int index) {
        if (index >= a.size()) {
            throw new CommandException(CommandSupport.SYNTAX_ERROR);
        }
        return a.get(index);
    }

    /** What a {@code GET} shows for a path: all matches as a list for a JSONPath, the one value for a legacy path. */
    private static Object selected(JsonDocument doc, JsonPath path) {
        List<JsonPath.Match> matches = doc.select(path);
        if (path.isLegacy()) {
            if (matches.isEmpty()) {
                throw pathMissing(path);
            }
            return matches.get(0).value();
        }
        List<Object> values = new ArrayList<>(matches.size());
        for (JsonPath.Match match : matches) {
            values.add(match.value());
        }
        return values;
    }

    /** {@code JSON.MGET key [key ...] path} */
    private RespValue mget(List<String> a) {
        JsonPath path = JsonPath.parse(a.get(a.size() - 1));
        List<RespValue> replies = new ArrayList<>();
        for (String key : a.subList(0, a.size() - 1)) {
            String text;
            try {
                text = withDocument(key, doc -> {
                    if (doc == null) {
                        return null;
                    }
                    List<JsonPath.Match> matches = doc.select(path);
                    if (path.isLegacy()) {
                        return matches.isEmpty() ? null : JsonWriter.toString(matches.get(0).value());
                    }
                    return JsonWriter.toString(selected(doc, path));
                });
            } catch (WrongTypeException notJson) {
                text = null;
            }
            replies.add(text == null ? new NullValue() : new BulkString(text));
        }
        return new ArrayValue(replies);
    }

    /** {@code JSON.DEL key [path]}, also {@code JSON.FORGET} */
    private RespValue del(List<String> a) {
        JsonPath path = pathOf(a, 1);
        Integer deleted = store.<Integer>mutate(a.get(0), ValueType.JSON, null, o -> ((JsonDocument) o).delete(path));
        return new IntegerValue(deleted == null ? 0 : deleted);
    }

    /** {@code JSON.TYPE key [path]} */
    private RespValue type(List<String> a) {
        JsonPath path = pathOf(a, 1);
        return withDocument(a.get(0), doc -> {
            if (doc == null) {
                return new NullValue();
            }
            List<JsonPath.Match> matches = doc.select(path);
            if (path.isLegacy()) {
                return matches.isEmpty() ? new NullValue() : new BulkString(Json.typeName(matches.get(0).value()));
            }
            List<RespValue> names = new ArrayList<>(matches.size());
            for (JsonPath.Match match : matches) {
                names.add(new BulkString(Json.typeName(match.value())));
            }
            return new ArrayValue(names);
        });
    }

    // numbers

    /** {@code JSON.NUMINCRBY key path number}, {@code JSON.NUMMULTBY key path number} */
    private RespValue number(List<String> a, Arithmetic operation) {
        JsonPath path = JsonPath.parse(a.get(1));
        Object operand;
        try {
            operand = JsonParser.parse(a.get(2)).value();
        } catch (JsonException notJson) {
            operand = null;
        }
        if (!Json.isNumber(operand)) {
            throw new CommandException("value is not a number");
        }
        Number number = (Number) operand;
        Outcome result = updateDocument(a.get(0), doc -> outcome(doc, path, doc.number(path, operation, number)));
        if (!path.isLegacy()) {
            return new BulkString(JsonWriter.toString(result.values()));
        }
        return perMatch(path, result, "number", value -> new BulkString(JsonWriter.toString(value)));
    }

    /** {@code JSON.TOGGLE key path} */
    private RespValue toggle(List<String> a) {
        JsonPath path = JsonPath.parse(a.get(1));
        Outcome result = updateDocument(a.get(0), doc -> outcome(doc, path, doc.toggle(path)));
        return perMatch(path, result, "boolean", value -> path.isLegacy()
                ? new BulkString(value.toString()) : new IntegerValue((Boolean) value ? 1 : 0));
    }

    // strings

    /** {@code JSON.STRAPPEND key [path] value} */
    private RespValue strAppend(List<String> a) {
        JsonPath path = a.size() == 3 ? JsonPath.parse(a.get(1)) : JsonPath.parse(ROOT);
        Object suffix = valueOf(PreparedWork.take(PreparedValues.class), a.get(a.size() - 1)).value();
        if (!(suffix instanceof String text)) {
            throw new CommandException("wrong type of value - expected a string but found " + Json.typeName(suffix));
        }
        Outcome result = updateDocument(a.get(0), doc -> outcome(doc, path, doc.appendToStrings(path, text)));
        return perMatch(path, result, "string", JsonCommands::integer);
    }

    /** {@code JSON.STRLEN key [path]} */
    private RespValue strLen(List<String> a) {
        JsonPath path = pathOf(a, 1);
        return readPerMatch(a.get(0), path, "string", matches -> {
            List<Object> lengths = new ArrayList<>();
            for (JsonPath.Match match : matches) {
                lengths.add(match.value() instanceof String s ? (Object) (long) s.length() : null);
            }
            return lengths;
        }, JsonCommands::integer);
    }

    /** A read-only command that answers per match; {@code nil} if the key does not exist. */
    private RespValue readPerMatch(String key, JsonPath path, String expected,
                                   Function<List<JsonPath.Match>, List<Object>> collector,
                                   Function<Object, RespValue> element) {
        return withDocument(key, doc -> {
            if (doc == null) {
                return new NullValue();
            }
            return perMatch(path, outcome(doc, path, collector.apply(doc.select(path))), expected, element);
        });
    }

    // -------------------------------------------------------------------------------------------------- arrays

    /** {@code JSON.ARRAPPEND key path value [value ...]} */
    private RespValue arrAppend(List<String> a) {
        JsonPath path = JsonPath.parse(a.get(1));
        PreparedValues prepared = PreparedWork.take(PreparedValues.class);
        List<Object> values = new ArrayList<>();
        int depth = 0;
        for (String text : a.subList(2, a.size())) {
            JsonParser.Parsed parsed = valueOf(prepared, text);
            values.add(parsed.value());
            depth = Math.max(depth, parsed.depth());
        }
        int valueDepth = depth;
        Outcome result = updateDocument(a.get(0), doc -> outcome(doc, path, doc.appendToArrays(path, values, valueDepth)));
        return perMatch(path, result, "array", JsonCommands::integer);
    }

    /** {@code JSON.ARRINSERT key path index value [value ...]} */
    private RespValue arrInsert(List<String> a) {
        JsonPath path = JsonPath.parse(a.get(1));
        long index = CommandSupport.parseLong(a.get(2));
        PreparedValues prepared = PreparedWork.take(PreparedValues.class);
        List<Object> values = new ArrayList<>();
        int depth = 0;
        for (String text : a.subList(3, a.size())) {
            JsonParser.Parsed parsed = valueOf(prepared, text);
            values.add(parsed.value());
            depth = Math.max(depth, parsed.depth());
        }
        int valueDepth = depth;
        Outcome result = updateDocument(a.get(0),
                doc -> outcome(doc, path, doc.insertIntoArrays(path, index, values, valueDepth)));
        return perMatch(path, result, "array", JsonCommands::integer);
    }

    /** {@code JSON.ARRINDEX key path value [start [stop]]} */
    private RespValue arrIndex(List<String> a) {
        JsonPath path = JsonPath.parse(a.get(1));
        Object value = JsonParser.parse(a.get(2)).value();
        long start = a.size() > 3 ? CommandSupport.parseLong(a.get(3)) : 0;
        long stop = a.size() > 4 ? CommandSupport.parseLong(a.get(4)) : 0;
        return withDocument(a.get(0), doc -> {
            if (doc == null) {
                return new NullValue();
            }
            return perMatch(path, outcome(doc, path, doc.indexInArrays(path, value, start, stop)), "array", JsonCommands::integer);
        });
    }

    /** {@code JSON.ARRLEN key [path]} */
    private RespValue arrLen(List<String> a) {
        JsonPath path = pathOf(a, 1);
        return readPerMatch(a.get(0), path, "array", matches -> {
            List<Object> lengths = new ArrayList<>();
            for (JsonPath.Match match : matches) {
                lengths.add(match.value() instanceof JsonArray array ? (Object) (long) array.size() : null);
            }
            return lengths;
        }, JsonCommands::integer);
    }

    /** {@code JSON.ARRPOP key [path [index]]} */
    private RespValue arrPop(List<String> a) {
        JsonPath path = pathOf(a, 1);
        long index = a.size() > 2 ? CommandSupport.parseLong(a.get(2)) : -1;
        Outcome result = updateDocument(a.get(0), doc -> outcome(doc, path, doc.popFromArrays(path, index)));
        if (path.isLegacy() && !result.values().isEmpty() && result.values().get(0) == null && "array".equals(result.foundType())) {
            return new NullValue(); // an empty array has nothing to pop
        }
        return perMatch(path, result, "array", value -> new BulkString(JsonWriter.toString(value)));
    }

    /** {@code JSON.ARRTRIM key path start stop} */
    private RespValue arrTrim(List<String> a) {
        JsonPath path = JsonPath.parse(a.get(1));
        long start = CommandSupport.parseLong(a.get(2));
        long stop = CommandSupport.parseLong(a.get(3));
        Outcome result = updateDocument(a.get(0), doc -> outcome(doc, path, doc.trimArrays(path, start, stop)));
        return perMatch(path, result, "array", JsonCommands::integer);
    }

    // objects

    /** {@code JSON.OBJKEYS key [path]} */
    private RespValue objKeys(List<String> a) {
        JsonPath path = pathOf(a, 1);
        return readPerMatch(a.get(0), path, "object", matches -> {
            List<Object> keys = new ArrayList<>();
            for (JsonPath.Match match : matches) {
                keys.add(match.value() instanceof JsonObject object ? object.keyList() : null);
            }
            return keys;
        }, value -> {
            @SuppressWarnings("unchecked") List<String> names = (List<String>) value;
            return ArrayValue.ofBulkStrings(names);
        });
    }

    /** {@code JSON.OBJLEN key [path]} */
    private RespValue objLen(List<String> a) {
        JsonPath path = pathOf(a, 1);
        return readPerMatch(a.get(0), path, "object", matches -> {
            List<Object> lengths = new ArrayList<>();
            for (JsonPath.Match match : matches) {
                lengths.add(match.value() instanceof JsonObject object ? (Object) (long) object.size() : null);
            }
            return lengths;
        }, JsonCommands::integer);
    }

    /** {@code JSON.CLEAR key [path]} */
    private RespValue clear(List<String> a) {
        JsonPath path = pathOf(a, 1);
        Integer cleared = store.<Integer>mutate(a.get(0), ValueType.JSON, null, o -> ((JsonDocument) o).clear(path));
        return new IntegerValue(cleared == null ? 0 : cleared);
    }

    /** {@code JSON.MERGE key path value}: RFC 7386 merge patch */
    private RespValue merge(List<String> a) {
        JsonPath path = JsonPath.parse(a.get(1));
        JsonParser.Parsed patch = valueOf(PreparedWork.take(PreparedValues.class), a.get(2));
        boolean[] ran = {false};
        store.<Object>mutate(a.get(0), ValueType.JSON, path.isRoot() ? JsonDocument::new : null, o -> {
            ran[0] = true;
            ((JsonDocument) o).merge(path, patch.value(), patch.depth());
            return null;
        });
        if (!ran[0]) {
            throw new CommandException("new objects must be created at the root");
        }
        return ok();
    }

    /** {@code JSON.DEBUG MEMORY key [path]}: the estimated bytes of the value (RedisJSON has more subcommands). */
    private RespValue debug(List<String> a) {
        if (!"MEMORY".equalsIgnoreCase(a.get(0))) {
            throw new CommandException("unknown subcommand '" + a.get(0) + "' for 'json.debug' command");
        }
        if (a.size() < 2) {
            throw new CommandException(CommandSupport.wrongArity("json.debug"));
        }
        JsonPath path = pathOf(a, 2);
        return withDocument(a.get(1), doc -> {
            if (doc == null) {
                return new IntegerValue(0);
            }
            List<JsonPath.Match> matches = doc.select(path);
            if (path.isLegacy()) {
                return new IntegerValue(matches.isEmpty() ? 0 : Json.sizeOf(matches.get(0).value()));
            }
            List<RespValue> sizes = new ArrayList<>(matches.size());
            for (JsonPath.Match match : matches) {
                sizes.add(new IntegerValue(Json.sizeOf(match.value())));
            }
            return new ArrayValue(sizes);
        });
    }
}
