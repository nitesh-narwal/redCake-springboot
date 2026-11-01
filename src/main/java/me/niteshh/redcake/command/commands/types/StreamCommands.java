package me.niteshh.redcake.command.commands.types;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandException;
import me.niteshh.redcake.command.CommandProvider;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.ArrayValue;
import me.niteshh.redcake.resp.ErrorValue;
import me.niteshh.redcake.resp.IntegerValue;
import me.niteshh.redcake.resp.NullArray;
import me.niteshh.redcake.resp.NullValue;
import me.niteshh.redcake.resp.RespValue;
import me.niteshh.redcake.resp.SimpleString;
import me.niteshh.redcake.store.KeyValueStore;
import me.niteshh.redcake.store.ValueType;
import me.niteshh.redcake.stream.Consumer;
import me.niteshh.redcake.stream.ConsumerGroup;
import me.niteshh.redcake.stream.Pending;
import me.niteshh.redcake.stream.Stream;
import me.niteshh.redcake.stream.StreamEntry;
import me.niteshh.redcake.stream.StreamException;
import me.niteshh.redcake.stream.StreamId;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import static me.niteshh.redcake.command.LambdaCommand.read;
import static me.niteshh.redcake.command.LambdaCommand.shrink;
import static me.niteshh.redcake.command.LambdaCommand.write;
import static me.niteshh.redcake.command.commands.types.StreamSupport.bulk;
import static me.niteshh.redcake.command.commands.types.StreamSupport.entries;
import static me.niteshh.redcake.command.commands.types.StreamSupport.exactId;
import static me.niteshh.redcake.command.commands.types.StreamSupport.id;
import static me.niteshh.redcake.command.commands.types.StreamSupport.integer;
import static me.niteshh.redcake.command.commands.types.StreamSupport.is;
import static me.niteshh.redcake.command.commands.types.StreamSupport.number;

/**
 * Streams, the first half: {@code XADD XLEN XRANGE XREVRANGE XDEL XTRIM XSETID XINFO}. The groups and the reading
 * commands are in {@link StreamGroupCommands}; the data structure and its rules are in {@link Stream}.
 *
 * <p>A stream is an append-only log of entries, each with an id and field/value pairs, kept in the order they were
 * added. Unlike a Pub/Sub channel it is durable (it is in the AOF, in a full sync, in every replica), and a reader
 * can come back later and ask for what it missed; with consumer groups the entries can be shared out between workers
 * and acknowledged one by one.
 *
 * <p>The semantics are those of Redis 7, ported from its source, and the error texts are Redis's. Differences:
 * {@code MAXLEN ~} and {@code MINID ~} trim exactly (a stream is never longer than asked), and
 * {@code radix-tree-keys} / {@code radix-tree-nodes} in {@code XINFO STREAM} report RedCake's blocks.
 *
 * <p>Writes run under the primary write lock like every write. {@code XADD} writes the id it chose into the command
 * before the command is logged and replicated ({@link #normalizeAdd}), so a replica or the AOF stores the same id and
 * not a second reading of its own clock.
 */
@Component
@RequiredArgsConstructor
public class StreamCommands implements CommandProvider {

    private static final ErrorValue SYNTAX = CommandSupport.SYNTAX_ERROR;

    private final KeyValueStore store;

    @Override
    public List<RedCakeCommand> commands() {
        return List.of(
                write("XADD", 4, -1, guarded(this::xadd)).normalizedBy(this::normalizeAdd),
                read("XLEN", 1, 1, guarded(a -> new IntegerValue(store.<Long>read(a.get(0), ValueType.STREAM,
                        o -> o == null ? 0L : ((Stream) o).length())))),
                read("XRANGE", 3, -1, guarded(a -> range(a, false))),
                read("XREVRANGE", 3, -1, guarded(a -> range(a, true))),
                shrink("XDEL", 2, -1, guarded(this::xdel)),
                shrink("XTRIM", 3, -1, guarded(this::xtrim)),
                shrink("XSETID", 2, -1, guarded(this::xsetid)),
                read("XINFO", 1, -1, guarded(this::xinfo))
        );
    }

    /** A stream rule that is not met is an error reply, not a crash. */
    static Function<List<String>, RespValue> guarded(Function<List<String>, RespValue> body) {
        return arguments -> {
            try {
                return body.apply(arguments);
            } catch (StreamException e) {
                return new ErrorValue(e.getMessage());
            }
        };
    }

    // -------------------------------------------------------- XADD and XTRIM

    /** The trimming asked for by {@code MAXLEN} or {@code MINID}. */
    record Trim(boolean byLength, long maxLen, StreamId minId, boolean approximate, long limit) {
    }

    /** What the arguments of {@code XADD} (or, without the id, {@code XTRIM}) say. */
    record AddArguments(Trim trim, boolean noMakeStream, int idIndex, StreamId id, boolean seqGiven, boolean idGiven) {
    }

    /**
     * Reads the options in front of the id of {@code XADD key [NOMKSTREAM] [MAXLEN|MINID [=|~] n [LIMIT c]] id ...}
     * (or all the arguments of {@code XTRIM}). The order of the checks and the texts of the errors are Redis's.
     *
     * @param a the arguments after the command name; {@code a[0]} is the key
     */
    static AddArguments parseAddOrTrim(List<String> a, boolean xadd) {
        boolean byLength = false;
        boolean haveTrim = false;
        long maxLen = 0;
        StreamId minId = null;
        boolean approximate = false;
        long limit = 0;
        boolean limitGiven = false;
        boolean noMakeStream = false;
        StreamId id = null;
        boolean seqGiven = true;
        boolean idGiven = false;

        int i = 1;
        for (; i < a.size(); i++) {
            int more = a.size() - 1 - i;
            String option = a.get(i);
            if (xadd && option.equals("*")) {
                break;
            } else if (is(option, "MAXLEN") && more > 0) {
                if (haveTrim) {
                    throw new CommandException("syntax error, MAXLEN and MINID options at the same time are not compatible");
                }
                approximate = false;
                String next = a.get(i + 1);
                if (more >= 2 && next.equals("~")) {
                    approximate = true;
                    i++;
                } else if (more >= 2 && next.equals("=")) {
                    i++;
                }
                maxLen = number(a.get(i + 1));
                if (maxLen < 0) {
                    throw new CommandException("The MAXLEN argument must be >= 0.");
                }
                i++;
                haveTrim = true;
                byLength = true;
            } else if (is(option, "MINID") && more > 0) {
                if (haveTrim) {
                    throw new CommandException("syntax error, MAXLEN and MINID options at the same time are not compatible");
                }
                approximate = false;
                String next = a.get(i + 1);
                if (more >= 2 && next.equals("~")) {
                    approximate = true;
                    i++;
                } else if (more >= 2 && next.equals("=")) {
                    i++;
                }
                minId = exactId(a.get(i + 1));
                i++;
                haveTrim = true;
                byLength = false;
            } else if (is(option, "LIMIT") && more > 0) {
                limit = number(a.get(i + 1));
                if (limit < 0) {
                    throw new CommandException("The LIMIT argument must be >= 0.");
                }
                limitGiven = true;
                i++;
            } else if (xadd && is(option, "NOMKSTREAM")) {
                noMakeStream = true;
            } else if (xadd) {
                StreamId.Parsed parsed = StreamId.parse(option, 0, true);
                if (parsed == null) {
                    throw new CommandException(StreamSupport.BAD_ID);
                }
                id = parsed.id();
                seqGiven = parsed.seqGiven();
                idGiven = true;
                break;
            } else {
                throw new CommandException(SYNTAX);
            }
        }

        if (limit != 0 && !haveTrim) {
            throw new CommandException("syntax error, LIMIT cannot be used without specifying a trimming strategy");
        }
        if (!xadd && !haveTrim) {
            throw new CommandException("syntax error, XTRIM must be called with a trimming strategy");
        }
        if (limitGiven && !approximate) {
            throw new CommandException("syntax error, LIMIT cannot be used without the special ~ option");
        }
        Trim trim = haveTrim ? new Trim(byLength, maxLen, minId, approximate, limit) : null;
        return new AddArguments(trim, noMakeStream, i, id, seqGiven, idGiven);
    }

    private static long trim(Stream stream, Trim trim) {
        return trim.byLength()
                ? stream.trimByLength(trim.maxLen(), trim.limit())
                : stream.trimByMinId(trim.minId(), trim.limit());
    }

    /** {@code XADD key [NOMKSTREAM] [MAXLEN|MINID [=|~] n [LIMIT c]] <id|*> field value [field value ...]} */
    private RespValue xadd(List<String> a) {
        AddArguments parsed = parseAddOrTrim(a, true);
        int fieldPosition = parsed.idIndex() + 1;
        int remaining = a.size() - fieldPosition;
        if (remaining < 2 || remaining % 2 == 1) {
            return CommandSupport.wrongArity("xadd");
        }
        if (parsed.idGiven() && parsed.seqGiven() && parsed.id().isZero()) {
            return new ErrorValue("The ID specified in XADD must be greater than 0-0");
        }
        String key = a.get(0);
        long now = System.currentTimeMillis();
        StreamId[] added = {null};
        store.<Object>mutate(key, ValueType.STREAM, parsed.noMakeStream() ? null : Stream::new, o -> {
            Stream stream = (Stream) o;
            added[0] = stream.add(parsed.idGiven() ? parsed.id() : null, parsed.seqGiven(), a, fieldPosition, now);
            if (parsed.trim() != null) {
                trim(stream, parsed.trim());
            }
            return null;
        });
        if (added[0] == null) {
            return new NullValue(); // NOMKSTREAM and there is no stream
        }
        store.waiters().wake(key); // readers blocked on this stream have something to read
        return id(added[0]);
    }

    /**
     * Puts the id the entry will get into the command, so that replicas and the AOF store this id and do not choose one
     * with a clock of their own. Runs under the write lock, where the stream cannot change before {@code xadd} runs,
     * and uses the very rule {@code xadd} uses. Never throws: a command that is wrong is left for {@code xadd} to refuse.
     */
    private List<String> normalizeAdd(List<String> command, long now) {
        try {
            List<String> a = command.subList(1, command.size());
            AddArguments parsed = parseAddOrTrim(a, true);
            if (!parsed.idGiven() || !parsed.seqGiven()) {
                StreamId last = store.<StreamId>read(a.get(0), ValueType.STREAM,
                        o -> o == null ? StreamId.ZERO : ((Stream) o).lastId());
                StreamId chosen = StreamSupport.nextIdOrNull(
                        last, parsed.idGiven() ? parsed.id() : null, parsed.seqGiven(), now);
                if (chosen != null) {
                    List<String> rewritten = new ArrayList<>(command);
                    rewritten.set(parsed.idIndex() + 1, chosen.toString());
                    return rewritten;
                }
            }
        } catch (RuntimeException e) {
            // let xadd report it
        }
        return command;
    }

    /** {@code XTRIM key MAXLEN|MINID [=|~] n [LIMIT c]} */
    private RespValue xtrim(List<String> a) {
        AddArguments parsed = parseAddOrTrim(a, false);
        Long removed = store.<Long>mutate(a.get(0), ValueType.STREAM, null, o -> trim((Stream) o, parsed.trim()));
        return new IntegerValue(removed == null ? 0 : removed);
    }

    // ---------------------------------------------------------- the others

    /** {@code XRANGE key start end [COUNT n]} and {@code XREVRANGE key end start [COUNT n]} */
    private RespValue range(List<String> a, boolean reverse) {
        StreamId start = StreamSupport.rangeStart(a.get(reverse ? 2 : 1));
        StreamId end = StreamSupport.rangeEnd(a.get(reverse ? 1 : 2));
        long count = -1;
        for (int j = 3; j < a.size(); j++) {
            int additional = a.size() - j - 1;
            if (is(a.get(j), "COUNT") && additional >= 1) {
                count = number(a.get(j + 1));
                if (count < 0) {
                    count = 0;
                }
                j++;
            } else {
                throw new CommandException(SYNTAX);
            }
        }
        long limit = count;
        return store.read(a.get(0), ValueType.STREAM, o -> {
            if (o == null) {
                return ArrayValue.empty();
            }
            if (limit == 0) {
                return new NullArray(); // what Redis answers to COUNT 0
            }
            return entries(((Stream) o).range(start, end, reverse, Math.max(limit, 0)));
        });
    }

    /** {@code XDEL key id [id ...]} */
    private RespValue xdel(List<String> a) {
        Integer deleted = store.<Integer>mutate(a.get(0), ValueType.STREAM, null, o -> {
            List<StreamId> ids = new ArrayList<>(a.size() - 1);
            for (String text : a.subList(1, a.size())) {
                ids.add(exactId(text)); // all ids are read before anything is deleted
            }
            return ((Stream) o).delete(ids);
        });
        return new IntegerValue(deleted == null ? 0 : deleted);
    }

    /** {@code XSETID key id [ENTRIESADDED n] [MAXDELETEDID id]} */
    private RespValue xsetid(List<String> a) {
        StreamId id = exactId(a.get(1));
        long entriesAdded = -1;
        StreamId maxDeleted = StreamId.ZERO;
        int i = 2;
        while (i < a.size()) {
            int more = a.size() - 1 - i;
            String option = a.get(i);
            if (is(option, "ENTRIESADDED") && more > 0) {
                entriesAdded = number(a.get(i + 1));
                if (entriesAdded < 0) {
                    throw new CommandException("entries_added must be positive");
                }
                i += 2;
            } else if (is(option, "MAXDELETEDID") && more > 0) {
                maxDeleted = exactId(a.get(i + 1));
                if (id.compareTo(maxDeleted) < 0) {
                    throw new CommandException(
                            "The ID specified in XSETID is smaller than the provided max_deleted_entry_id");
                }
                i += 2;
            } else {
                throw new CommandException(SYNTAX);
            }
        }
        long added = entriesAdded;
        StreamId deleted = maxDeleted;
        boolean[] ran = {false};
        store.<Object>mutate(a.get(0), ValueType.STREAM, null, o -> {
            ran[0] = true;
            ((Stream) o).setId(id, added, deleted);
            return null;
        });
        if (!ran[0]) {
            throw new CommandException("no such key");
        }
        return new SimpleString("OK");
    }

    // ------------------------------------------------------------------ XINFO

    private static final List<String> XINFO_HELP = List.of(
            "XINFO <subcommand> [<arg> [value] [opt] ...]. Subcommands are:",
            "CONSUMERS <key> <groupname>",
            "    Show consumers of <groupname>.",
            "GROUPS <key>",
            "    Show the stream consumer groups.",
            "STREAM <key> [FULL [COUNT <count>]",
            "    Show information about the stream.",
            "HELP",
            "    Print this help.");

    /** {@code XINFO STREAM key [FULL [COUNT n]]}, {@code XINFO GROUPS key}, {@code XINFO CONSUMERS key group} */
    private RespValue xinfo(List<String> a) {
        String sub = a.get(0).toUpperCase(Locale.ROOT);
        switch (sub) {
            case "HELP" -> {
                return help(XINFO_HELP);
            }
            case "STREAM" -> {
                if (a.size() < 2) {
                    return CommandSupport.wrongArity("xinfo|stream");
                }
            }
            case "GROUPS" -> {
                if (a.size() != 2) {
                    return CommandSupport.wrongArity("xinfo|groups");
                }
            }
            case "CONSUMERS" -> {
                if (a.size() != 3) {
                    return CommandSupport.wrongArity("xinfo|consumers");
                }
            }
            default -> {
                return new ErrorValue("unknown subcommand '" + a.get(0) + "'. Try XINFO HELP.");
            }
        }
        String key = a.get(1);
        return store.read(key, ValueType.STREAM, o -> {
            if (o == null) {
                return new ErrorValue("no such key");
            }
            Stream stream = (Stream) o;
            return switch (sub) {
                case "GROUPS" -> groupsInfo(stream);
                case "CONSUMERS" -> consumersInfo(stream, key, a.get(2));
                default -> streamInfo(stream, a);
            };
        });
    }

    static RespValue help(List<String> lines) {
        List<RespValue> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(new SimpleString(line));
        }
        return new ArrayValue(out);
    }

    private static void put(List<RespValue> out, String name, RespValue value) {
        out.add(bulk(name));
        out.add(value);
    }

    private RespValue streamInfo(Stream stream, List<String> a) {
        boolean full = false;
        long count = 10;
        if (a.size() > 2) {
            if (a.size() != 3 && a.size() != 5 || !is(a.get(2), "FULL")) {
                return syntaxFor("stream");
            }
            full = true;
            if (a.size() == 5) {
                if (!is(a.get(3), "COUNT")) {
                    return syntaxFor("stream");
                }
                count = number(a.get(4));
                if (count < 0) {
                    count = 10;
                }
            }
        }
        List<RespValue> out = new ArrayList<>();
        put(out, "length", integer(stream.length()));
        put(out, "radix-tree-keys", integer(stream.blockCount()));
        put(out, "radix-tree-nodes", integer(stream.blockCount() + 1L));
        put(out, "last-generated-id", id(stream.lastId()));
        put(out, "max-deleted-entry-id", id(stream.maxDeletedId()));
        put(out, "entries-added", integer(stream.entriesAdded()));
        put(out, "recorded-first-entry-id", id(stream.firstId()));
        if (!full) {
            put(out, "groups", integer(stream.groupCount()));
            List<StreamEntry> first = stream.range(StreamId.ZERO, StreamId.MAX, false, 1);
            put(out, "first-entry", first.isEmpty() ? new NullValue() : StreamSupport.entry(first.getFirst()));
            List<StreamEntry> last = stream.range(StreamId.ZERO, StreamId.MAX, true, 1);
            put(out, "last-entry", last.isEmpty() ? new NullValue() : StreamSupport.entry(last.getFirst()));
        } else {
            put(out, "entries", entries(stream.range(StreamId.ZERO, StreamId.MAX, false, count)));
            List<RespValue> groups = new ArrayList<>();
            for (ConsumerGroup group : stream.groups()) {
                groups.add(fullGroupInfo(stream, group, count));
            }
            put(out, "groups", new ArrayValue(groups));
        }
        return new ArrayValue(out);
    }

    private static RespValue fullGroupInfo(Stream stream, ConsumerGroup group, long count) {
        List<RespValue> out = new ArrayList<>();
        put(out, "name", bulk(group.name()));
        put(out, "last-delivered-id", id(group.lastId()));
        put(out, "entries-read", StreamSupport.integerOrNil(group.entriesRead()));
        put(out, "lag", StreamSupport.integerOrNil(stream.lag(group)));
        put(out, "pel-count", integer(group.pendingCount()));
        List<RespValue> pending = new ArrayList<>();
        for (Map.Entry<StreamId, Pending> held : group.pending().entrySet()) {
            if (count != 0 && pending.size() >= count) {
                break;
            }
            Pending p = held.getValue();
            pending.add(new ArrayValue(List.of(id(held.getKey()), bulk(p.consumer().name()),
                    integer(p.deliveryTime()), integer(p.deliveryCount()))));
        }
        put(out, "pending", new ArrayValue(pending));
        List<RespValue> consumers = new ArrayList<>();
        for (Consumer consumer : group.consumers()) {
            List<RespValue> info = new ArrayList<>();
            put(info, "name", bulk(consumer.name()));
            put(info, "seen-time", integer(consumer.seenTime()));
            put(info, "active-time", integer(consumer.activeTime()));
            put(info, "pel-count", integer(consumer.pendingCount()));
            List<RespValue> held = new ArrayList<>();
            for (Map.Entry<StreamId, Pending> entry : consumer.pending().entrySet()) {
                if (count != 0 && held.size() >= count) {
                    break;
                }
                held.add(new ArrayValue(List.of(id(entry.getKey()),
                        integer(entry.getValue().deliveryTime()), integer(entry.getValue().deliveryCount()))));
            }
            put(info, "pending", new ArrayValue(held));
            consumers.add(new ArrayValue(info));
        }
        put(out, "consumers", new ArrayValue(consumers));
        return new ArrayValue(out);
    }

    private static RespValue groupsInfo(Stream stream) {
        List<RespValue> groups = new ArrayList<>();
        for (ConsumerGroup group : stream.groups()) {
            List<RespValue> info = new ArrayList<>();
            put(info, "name", bulk(group.name()));
            put(info, "consumers", integer(group.consumers().size()));
            put(info, "pending", integer(group.pendingCount()));
            put(info, "last-delivered-id", id(group.lastId()));
            put(info, "entries-read", StreamSupport.integerOrNil(group.entriesRead()));
            put(info, "lag", StreamSupport.integerOrNil(stream.lag(group)));
            groups.add(new ArrayValue(info));
        }
        return new ArrayValue(groups);
    }

    private static RespValue consumersInfo(Stream stream, String key, String groupName) {
        ConsumerGroup group = stream.group(groupName);
        if (group == null) {
            return new ErrorValue("NOGROUP No such consumer group '" + groupName + "' for key name '" + key + "'");
        }
        long now = System.currentTimeMillis();
        List<RespValue> consumers = new ArrayList<>();
        for (Consumer consumer : group.consumers()) {
            long inactive = consumer.activeTime() != -1 ? now - consumer.activeTime() : consumer.activeTime();
            List<RespValue> info = new ArrayList<>();
            put(info, "name", bulk(consumer.name()));
            put(info, "pending", integer(consumer.pendingCount()));
            put(info, "idle", integer(Math.max(0, now - consumer.seenTime())));
            put(info, "inactive", integer(inactive));
            consumers.add(new ArrayValue(info));
        }
        return new ArrayValue(consumers);
    }

    private static ErrorValue syntaxFor(String sub) {
        return new ErrorValue("unknown subcommand or wrong number of arguments for '" + sub + "'. Try XINFO HELP.");
    }
}
