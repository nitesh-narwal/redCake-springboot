package me.niteshh.redcake.command.commands.types;

import me.niteshh.redcake.command.CommandException;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.resp.ArrayValue;
import me.niteshh.redcake.resp.BulkString;
import me.niteshh.redcake.resp.ErrorValue;
import me.niteshh.redcake.resp.IntegerValue;
import me.niteshh.redcake.resp.NullValue;
import me.niteshh.redcake.resp.RespValue;
import me.niteshh.redcake.stream.Stream;
import me.niteshh.redcake.stream.StreamEntry;
import me.niteshh.redcake.stream.StreamId;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the stream commands share: reading ids and numbers the way Redis does (same error texts - clients and scripts
 * match on them), and building the replies.
 */
final class StreamSupport {

    static final ErrorValue BAD_ID = new ErrorValue("Invalid stream ID specified as stream command argument");

    private StreamSupport() {
    }

    // ------------------------------------------------------------- arguments

    static boolean is(String argument, String keyword) {
        return argument.equalsIgnoreCase(keyword);
    }

    static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /** An id that names one entry: {@code ms} or {@code ms-seq}; neither {@code -}, {@code +} nor {@code ms-*}. */
    static StreamId exactId(String text) {
        StreamId id = StreamId.parseExact(text);
        if (id == null) {
            throw new CommandException(BAD_ID);
        }
        return id;
    }

    /** An id for {@code XGROUP SETID}: like {@link #exactId} but {@code -} is {@code 0-0} and {@code +} the largest. */
    static StreamId looseId(String text) {
        if ("-".equals(text)) {
            return StreamId.ZERO;
        }
        if ("+".equals(text)) {
            return StreamId.MAX;
        }
        return exactId(text);
    }

    /** One end of a range: an id, possibly written {@code (id} for "not including it". */
    record Bound(StreamId id, boolean exclusive) {
    }

    /**
     * Reads the start or the end of a range: {@code -}, {@code +}, {@code ms}, {@code ms-seq}, or any of the last two
     * after a {@code (}. A bare {@code ms} means the first id with that millisecond as a start
     * ({@code missingSeq} 0) and the last one as an end (-1 = the largest sequence).
     */
    static Bound bound(String text, long missingSeq) {
        if (text.length() > 1 && text.charAt(0) == '(') {
            StreamId.Parsed parsed = StreamId.parse(text.substring(1), missingSeq, false);
            if (parsed == null) {
                throw new CommandException(BAD_ID);
            }
            return new Bound(parsed.id(), true);
        }
        if ("-".equals(text)) {
            return new Bound(StreamId.ZERO, false);
        }
        if ("+".equals(text)) {
            return new Bound(StreamId.MAX, false);
        }
        StreamId.Parsed parsed = StreamId.parse(text, missingSeq, false);
        if (parsed == null) {
            throw new CommandException(BAD_ID);
        }
        return new Bound(parsed.id(), false);
    }

    /** The first id a range starting at {@code text} includes. */
    static StreamId rangeStart(String text) {
        Bound bound = bound(text, 0);
        if (!bound.exclusive()) {
            return bound.id();
        }
        StreamId next = bound.id().next();
        if (next == null) {
            throw new CommandException("invalid start ID for the interval");
        }
        return next;
    }

    /** The last id a range ending at {@code text} includes. */
    static StreamId rangeEnd(String text) {
        Bound bound = bound(text, -1L);
        if (!bound.exclusive()) {
            return bound.id();
        }
        StreamId previous = bound.id().previous();
        if (previous == null) {
            throw new CommandException("invalid end ID for the interval");
        }
        return previous;
    }

    static long number(String text) {
        return CommandSupport.parseLong(text);
    }

    /** A number with its own error text, as {@code XCLAIM}'s options have. */
    static long number(String text, String errorMessage) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new CommandException(errorMessage);
        }
    }

    // --------------------------------------------------------------- replies

    static RespValue id(StreamId id) {
        return new BulkString(id.toString());
    }

    /** {@code [id, [field, value, ...]]} */
    static RespValue entry(StreamEntry entry) {
        String[] fields = entry.fields();
        List<RespValue> pairs = new ArrayList<>(fields.length);
        for (String field : fields) {
            pairs.add(new BulkString(field));
        }
        return new ArrayValue(List.of(id(entry.id()), new ArrayValue(pairs)));
    }

    static ArrayValue entries(List<StreamEntry> entries) {
        List<RespValue> out = new ArrayList<>(entries.size());
        for (StreamEntry entry : entries) {
            out.add(entry(entry));
        }
        return new ArrayValue(out);
    }

    static RespValue bulk(String text) {
        return new BulkString(text);
    }

    static RespValue integer(long value) {
        return new IntegerValue(value);
    }

    /** A counter that is -1 when it is not known, shown as nil. */
    static RespValue integerOrNil(long value) {
        return value < 0 ? new NullValue() : new IntegerValue(value);
    }

    static ErrorValue noGroup(String key, String group) {
        return new ErrorValue("NOGROUP No such key '" + key + "' or consumer group '" + group + "'");
    }

    /** The commands that rebuild or change a group, as the AOF and replicas get them. */
    static List<String> command(String... words) {
        return List.of(words);
    }

    /** The id the stream will get next, for {@code XADD}'s normalization; see {@link Stream#nextId}. */
    static StreamId nextIdOrNull(StreamId last, StreamId requested, boolean seqGiven, long now) {
        try {
            return Stream.nextId(last, requested, seqGiven, now);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
