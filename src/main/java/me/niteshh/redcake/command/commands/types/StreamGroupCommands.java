package me.niteshh.redcake.command.commands.types;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.BlockingRequest;
import me.niteshh.redcake.command.CommandException;
import me.niteshh.redcake.command.CommandProvider;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.Propagation;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.ArrayValue;
import me.niteshh.redcake.resp.BulkString;
import me.niteshh.redcake.resp.ErrorValue;
import me.niteshh.redcake.resp.IntegerValue;
import me.niteshh.redcake.resp.NullArray;
import me.niteshh.redcake.resp.NullValue;
import me.niteshh.redcake.resp.RespValue;
import me.niteshh.redcake.resp.SimpleString;
import me.niteshh.redcake.store.KeyValueStore;
import me.niteshh.redcake.store.ValueType;
import me.niteshh.redcake.store.WrongTypeException;
import me.niteshh.redcake.stream.Consumer;
import me.niteshh.redcake.stream.ConsumerGroup;
import me.niteshh.redcake.stream.Stream;
import me.niteshh.redcake.stream.StreamEntry;
import me.niteshh.redcake.stream.StreamId;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

import static me.niteshh.redcake.command.LambdaCommand.adminWrite;
import static me.niteshh.redcake.command.LambdaCommand.read;
import static me.niteshh.redcake.command.LambdaCommand.shrink;
import static me.niteshh.redcake.command.LambdaCommand.write;
import static me.niteshh.redcake.command.commands.types.StreamCommands.guarded;
import static me.niteshh.redcake.command.commands.types.StreamSupport.bulk;
import static me.niteshh.redcake.command.commands.types.StreamSupport.entries;
import static me.niteshh.redcake.command.commands.types.StreamSupport.exactId;
import static me.niteshh.redcake.command.commands.types.StreamSupport.id;
import static me.niteshh.redcake.command.commands.types.StreamSupport.integer;
import static me.niteshh.redcake.command.commands.types.StreamSupport.is;
import static me.niteshh.redcake.command.commands.types.StreamSupport.number;

/**
 * Streams, the second half: consumer groups and the commands that read - {@code XGROUP XREAD XREADGROUP XACK XPENDING
 * XCLAIM XAUTOCLAIM}.
 *
 * <h3>The job-queue life cycle</h3>
 * <pre>
 *   XADD jobs * task ...                      producer appends
 *   XREADGROUP GROUP g w1 BLOCK 5000 STREAMS jobs >   a worker gets a new entry; it is now PENDING for w1
 *   XACK jobs g id                            the worker finished: no longer pending
 *   XAUTOCLAIM jobs g w2 60000 0              w1 died: w2 takes what has been pending for over a minute
 * </pre>
 * Pub/Sub forgets a message nobody was listening for. Here the entry stays in the stream, the group remembers what
 * was handed to whom and when, and nothing is lost if a worker never answers - it is simply claimed again. Delivery
 * is therefore <em>at least once</em>: a worker that is slow, not dead, will see its entry go to someone else.
 *
 * <h3>Blocking</h3>
 * {@code XREAD ... BLOCK ms} and {@code XREADGROUP ... BLOCK ms} wait for entries without holding a lock (see
 * {@link BlockingRequest}); {@code BLOCK 0} waits until there is something. Inside {@code MULTI} they do not wait.
 *
 * <h3>Replication</h3>
 * {@code XREADGROUP}, {@code XCLAIM} and {@code XAUTOCLAIM} depend on the clock and on what the stream holds at that
 * moment, so they do not replicate as themselves: they report <em>what they did</em> through {@link Propagation} as
 * {@code XCLAIM ... TIME t RETRYCOUNT n FORCE JUSTID}, {@code XGROUP SETID} and {@code XGROUP CREATECONSUMER}, which
 * replicas and the AOF replay deterministically (as Redis does).
 */
@Component
@RequiredArgsConstructor
public class StreamGroupCommands implements CommandProvider {

    private static final ErrorValue SYNTAX = CommandSupport.SYNTAX_ERROR;

    private static final List<String> XGROUP_HELP = List.of(
            "XGROUP <subcommand> [<arg> [value] [opt] ...]. Subcommands are:",
            "CREATE <key> <groupname> <id|$> [option]",
            "    Create a new consumer group. Options are:",
            "    * MKSTREAM",
            "      Create the empty stream if it does not exist.",
            "    * ENTRIESREAD entries_read",
            "      Set the group's entries_read counter (internal use).",
            "CREATECONSUMER <key> <groupname> <consumer>",
            "    Create a new consumer in the specified group.",
            "DELCONSUMER <key> <groupname> <consumer>",
            "    Remove the specified consumer.",
            "DESTROY <key> <groupname>",
            "    Remove the specified group.",
            "SETID <key> <groupname> <id|$> [ENTRIESREAD entries_read]",
            "    Set the current group ID and entries_read counter.",
            "HELP",
            "    Print this help.");

    /** The most entries {@code XAUTOCLAIM} claims when no {@code COUNT} is given. */
    private static final long AUTOCLAIM_DEFAULT_COUNT = 100;

    private final KeyValueStore store;

    @Override
    public List<RedCakeCommand> commands() {
        return List.of(
                write("XGROUP", 1, -1, guarded(this::xgroup)),
                read("XREAD", 3, -1, guarded(a -> xread(a))).blockingBy(c -> blockingRead(c, false)),
                write("XREADGROUP", 6, -1, guarded(this::xreadgroup)).blockingBy(c -> blockingRead(c, true)),
                shrink("XACK", 3, -1, guarded(this::xack)),
                read("XPENDING", 2, 8, guarded(this::xpending)),
                write("XCLAIM", 5, -1, guarded(this::xclaim)),
                write("XAUTOCLAIM", 5, 8, guarded(this::xautoclaim)),
                adminWrite("XRESTORE", 6, 6, guarded(this::xrestore))
        );
    }

    // ------------------------------------------------------------------ XGROUP

    /**
     * {@code XGROUP CREATE | SETID | DESTROY | CREATECONSUMER | DELCONSUMER | HELP}. The checks come in Redis's order:
     * the number of arguments, the options, then the key (it must exist, except for {@code CREATE ... MKSTREAM}), then
     * the group, then the id.
     */
    private RespValue xgroup(List<String> a) {
        String name = a.get(0);
        String sub = name.toUpperCase(Locale.ROOT);
        switch (sub) {
            case "HELP" -> {
                return StreamCommands.help(XGROUP_HELP);
            }
            case "CREATE", "SETID" -> {
                if (a.size() < 4) {
                    return CommandSupport.wrongArity("xgroup|" + sub.toLowerCase(Locale.ROOT));
                }
            }
            case "DESTROY" -> {
                if (a.size() != 3) {
                    return CommandSupport.wrongArity("xgroup|destroy");
                }
            }
            case "CREATECONSUMER", "DELCONSUMER" -> {
                if (a.size() != 4) {
                    return CommandSupport.wrongArity("xgroup|" + sub.toLowerCase(Locale.ROOT));
                }
            }
            default -> {
                return new ErrorValue("unknown subcommand '" + name + "'. Try XGROUP HELP.");
            }
        }

        boolean create = sub.equals("CREATE");
        boolean setId = sub.equals("SETID");
        boolean makeStream = false;
        long entriesRead = -1;
        for (int i = 4; i < a.size(); ) {
            if (create && is(a.get(i), "MKSTREAM")) {
                makeStream = true;
                i++;
            } else if ((create || setId) && is(a.get(i), "ENTRIESREAD") && i + 1 < a.size()) {
                entriesRead = number(a.get(i + 1));
                if (entriesRead < 0 && entriesRead != -1) {
                    return new ErrorValue("value for ENTRIESREAD must be positive or -1");
                }
                i += 2;
            } else {
                return new ErrorValue("unknown subcommand or wrong number of arguments for '" + name
                        + "'. Try XGROUP HELP.");
            }
        }

        String key = a.get(1);
        String group = a.get(2);
        long read = entriesRead;
        long now = System.currentTimeMillis();
        boolean[] ran = {false};
        RespValue reply = store.<RespValue>mutate(key, ValueType.STREAM, makeStream ? Stream::new : null, o -> {
            ran[0] = true;
            Stream stream = (Stream) o;
            if (create) {
                StreamId position = "$".equals(a.get(3)) ? stream.lastId() : exactId(a.get(3));
                if (stream.createGroup(group, position, read) == null) {
                    throw new CommandException("BUSYGROUP Consumer Group name already exists");
                }
                return new SimpleString("OK");
            }
            if (sub.equals("DESTROY")) {
                boolean destroyed = stream.destroyGroup(group);
                if (destroyed) {
                    store.waiters().wake(key); // readers blocked on the group are told it is gone
                }
                return new IntegerValue(destroyed ? 1 : 0);
            }
            ConsumerGroup found = stream.group(group);
            if (found == null) {
                throw new CommandException("NOGROUP No such consumer group '" + group + "' for key name '" + key + "'");
            }
            switch (sub) {
                case "SETID" -> {
                    StreamId position = "$".equals(a.get(3)) ? stream.lastId() : StreamSupport.looseId(a.get(3));
                    stream.setGroupPosition(found, position, read);
                    store.waiters().wake(key); // going back makes entries deliverable again
                    return new SimpleString("OK");
                }
                case "CREATECONSUMER" -> {
                    return new IntegerValue(stream.createConsumer(found, a.get(3), now) != null ? 1 : 0);
                }
                default -> { // DELCONSUMER
                    Consumer consumer = found.consumer(a.get(3));
                    return new IntegerValue(consumer == null ? 0 : stream.deleteConsumer(found, consumer));
                }
            }
        });
        if (!ran[0]) {
            throw new CommandException("The XGROUP subcommand requires the key to exist. Note that for CREATE you "
                    + "may want to use the MKSTREAM option to create an empty stream automatically.");
        }
        return reply;
    }

    // ------------------------------------------------------- XREAD, XREADGROUP

    /** What the arguments of {@code XREAD} / {@code XREADGROUP} say. */
    private record ReadArguments(
            String group, String consumer, boolean noAck, long count, long blockMillis, int streamsIndex,
            int streamCount) {

        String key(List<String> a, int i) {
            return a.get(streamsIndex + i);
        }

        String id(List<String> a, int i) {
            return a.get(streamsIndex + streamCount + i);
        }
    }

    private static ReadArguments parseRead(List<String> a, boolean xreadgroup) {
        long timeout = -1;
        long count = 0;
        int streamsIndex = 0;
        int streamCount = 0;
        boolean noAck = false;
        String group = null;
        String consumer = null;
        for (int i = 0; i < a.size(); i++) {
            int more = a.size() - i - 1;
            String option = a.get(i);
            if (is(option, "BLOCK") && more > 0) {
                i++;
                timeout = number(a.get(i), "timeout is not an integer or out of range");
                if (timeout < 0) {
                    throw new CommandException("timeout is negative");
                }
                if (timeout > Long.MAX_VALUE - System.currentTimeMillis()) {
                    throw new CommandException("timeout is out of range");
                }
            } else if (is(option, "COUNT") && more > 0) {
                i++;
                count = number(a.get(i));
                if (count < 0) {
                    count = 0;
                }
            } else if (is(option, "STREAMS") && more > 0) {
                streamsIndex = i + 1;
                streamCount = a.size() - streamsIndex;
                if (streamCount % 2 != 0) {
                    throw new CommandException("Unbalanced '" + (xreadgroup ? "xreadgroup" : "xread")
                            + "' list of streams: for each stream key an ID or '" + (xreadgroup ? '>' : '$')
                            + "' must be specified.");
                }
                streamCount /= 2;
                break;
            } else if (is(option, "GROUP") && more >= 2) {
                if (!xreadgroup) {
                    throw new CommandException("The GROUP option is only supported by XREADGROUP. You called XREAD instead.");
                }
                group = a.get(i + 1);
                consumer = a.get(i + 2);
                i += 2;
            } else if (is(option, "NOACK")) {
                if (!xreadgroup) {
                    throw new CommandException("The NOACK option is only supported by XREADGROUP. You called XREAD instead.");
                }
                noAck = true;
            } else {
                throw new CommandException(SYNTAX);
            }
        }
        if (streamsIndex == 0) {
            throw new CommandException(SYNTAX);
        }
        if (xreadgroup && group == null) {
            throw new CommandException("Missing GROUP option for XREADGROUP");
        }
        return new ReadArguments(group, consumer, noAck, count, timeout, streamsIndex, streamCount);
    }

    /** The id after which {@code XREAD} should read, for a written id, {@code $} or {@code +}. */
    private static StreamId readFrom(String token, Stream stream) {
        if (token.equals("$")) {
            return stream == null ? StreamId.ZERO : stream.lastId();
        }
        if (token.equals("+")) {
            if (stream == null) {
                return StreamId.ZERO;
            }
            StreamId before = stream.lastId().previous();
            if (before == null) {
                throw new CommandException("the stream last element ID is 0-0");
            }
            return before;
        }
        if (token.equals(">")) {
            throw new CommandException("The > ID can be specified only when calling XREADGROUP using the GROUP "
                    + "<group> <consumer> option.");
        }
        return exactId(token);
    }

    /** {@code XREAD [COUNT n] [BLOCK ms] STREAMS key [key ...] id [id ...]}; {@code BLOCK} is handled by the server. */
    private RespValue xread(List<String> a) {
        ReadArguments r = parseRead(a, false);
        int n = r.streamCount();
        StreamId[] after = new StreamId[n];
        for (int i = 0; i < n; i++) {
            String token = r.id(a, i);
            after[i] = store.<StreamId>read(r.key(a, i), ValueType.STREAM, o -> readFrom(token, (Stream) o));
        }
        List<RespValue> replies = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            StreamId from = after[i];
            long count = r.count();
            List<StreamEntry> found = store.<List<StreamEntry>>read(r.key(a, i), ValueType.STREAM, o -> {
                Stream stream = (Stream) o;
                StreamId last = stream == null ? null : stream.lastEntryId();
                if (last == null || last.compareTo(from) <= 0) {
                    return null;
                }
                return stream.range(from.next(), StreamId.MAX, false, count);
            });
            if (found != null && !found.isEmpty()) {
                replies.add(new ArrayValue(List.of(bulk(r.key(a, i)), entries(found))));
            }
        }
        return replies.isEmpty() ? new NullArray() : new ArrayValue(replies);
    }

    private static final String MEANINGLESS_TAIL = " ID is meaningless in the context of XREADGROUP: you want to read "
            + "the history of this consumer by specifying a proper ID, or use the > ID to get new messages. The ";

    /**
     * {@code XREADGROUP GROUP g c [COUNT n] [BLOCK ms] [NOACK] STREAMS key [key ...] id [id ...]}: {@code >} hands the
     * consumer the entries nobody in the group has been given; any other id returns the consumer's own pending
     * entries after it. Everything is checked before anything changes. Replicas and the AOF get the result
     * ({@code XCLAIM ... FORCE JUSTID}, {@code XGROUP SETID}), not this command.
     */
    private RespValue xreadgroup(List<String> a) {
        ReadArguments r = parseRead(a, true);
        int n = r.streamCount();
        String groupName = r.group();
        String consumerName = r.consumer();

        StreamId[] history = new StreamId[n]; // null for ">"
        for (int i = 0; i < n; i++) {
            String key = r.key(a, i);
            String token = r.id(a, i);
            store.<Object>read(key, ValueType.STREAM, o -> {
                if (o == null || ((Stream) o).group(groupName) == null) {
                    throw new CommandException("NOGROUP No such key '" + key + "' or consumer group '" + groupName
                            + "' in XREADGROUP with GROUP option");
                }
                return null;
            });
            if (token.equals("$")) {
                throw new CommandException("The $" + MEANINGLESS_TAIL + "$ ID would just return an empty result set.");
            } else if (token.equals("+")) {
                throw new CommandException("The +" + MEANINGLESS_TAIL + "+ ID would just return an empty result set.");
            } else if (!token.equals(">")) {
                history[i] = exactId(token);
            }
        }

        long now = System.currentTimeMillis();
        List<List<String>> effects = new ArrayList<>();
        List<RespValue> replies = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                String key = r.key(a, i);
                StreamId from = history[i];
                RespValue served = store.<RespValue>mutate(key, ValueType.STREAM, null, o ->
                        serveGroup(key, (Stream) o, groupName, consumerName, from, r, now, effects));
                if (served != null) {
                    replies.add(new ArrayValue(List.of(bulk(key), served)));
                }
            }
        } finally {
            Propagation.instead(effects); // what was done, even if a later stream failed
        }
        return replies.isEmpty() ? new NullArray() : new ArrayValue(replies);
    }

    /** One stream of an {@code XREADGROUP}; {@code null} if there was nothing to give. */
    private static RespValue serveGroup(
            String key, Stream stream, String groupName, String consumerName, StreamId from, ReadArguments r, long now,
            List<List<String>> effects) {
        ConsumerGroup group = stream.group(groupName);
        Consumer consumer = group.consumer(consumerName);
        if (consumer == null) {
            consumer = stream.createConsumer(group, consumerName, now);
            effects.add(List.of("XGROUP", "CREATECONSUMER", key, groupName, consumerName));
        }
        stream.touch(consumer, now);

        if (from != null) { // the consumer's own history
            StreamId start = from.next();
            List<Stream.HistoryItem> items = start == null
                    ? List.of() : stream.deliverHistory(consumer, start, r.count(), now);
            List<RespValue> out = new ArrayList<>(items.size());
            for (Stream.HistoryItem item : items) {
                if (item.entry() == null) {
                    out.add(new ArrayValue(List.of(id(item.id()), new NullArray())));
                } else {
                    out.add(StreamSupport.entry(item.entry()));
                    effects.add(claimEffect(key, groupName, consumerName, item.id(), item.deliveryTime(),
                            item.deliveryCount(), false, null));
                }
            }
            return new ArrayValue(out);
        }

        if (!stream.hasUndelivered(group)) {
            return null;
        }
        Stream.DeliveryResult result = stream.deliverNew(group, consumer, r.count(), r.noAck(), now);
        List<RespValue> out = new ArrayList<>(result.deliveries().size());
        for (Stream.Delivery delivery : result.deliveries()) {
            out.add(StreamSupport.entry(delivery.entry()));
            if (!r.noAck()) {
                effects.add(claimEffect(key, groupName, consumerName, delivery.entry().id(), delivery.deliveryTime(),
                        delivery.deliveryCount(), true, delivery.groupLastId()));
            }
        }
        if (result.positionMoved()) {
            effects.add(setIdEffect(key, groupName, result.lastId(), result.entriesRead()));
        }
        return out.isEmpty() ? null : new ArrayValue(out);
    }

    /**
     * {@code XCLAIM key group consumer 0 id TIME t RETRYCOUNT n [FORCE] JUSTID [LASTID id]}: gives the entry to the
     * consumer with exactly this delivery time and count, whatever the clock of whoever replays it says.
     */
    private static List<String> claimEffect(
            String key, String group, String consumer, StreamId id, long time, long count, boolean force,
            StreamId lastId) {
        List<String> command = new ArrayList<>(List.of("XCLAIM", key, group, consumer, "0", id.toString(),
                "TIME", Long.toString(time), "RETRYCOUNT", Long.toString(count)));
        if (force) {
            command.add("FORCE");
        }
        command.add("JUSTID");
        if (lastId != null) {
            command.add("LASTID");
            command.add(lastId.toString());
        }
        return command;
    }

    private static List<String> setIdEffect(String key, String group, StreamId lastId, long entriesRead) {
        return List.of("XGROUP", "SETID", key, group, lastId.toString(), "ENTRIESREAD", Long.toString(entriesRead));
    }

    /**
     * Whether (and for what) this {@code XREAD} / {@code XREADGROUP} line wants to wait. {@code XREAD} resolves
     * {@code $} and {@code +} to the ids they mean <em>now</em>; left as they are, the entry that arrives while waiting
     * would become "the last id" and be skipped.
     */
    private BlockingRequest blockingRead(List<String> command, boolean xreadgroup) {
        try {
            List<String> a = command.subList(1, command.size());
            ReadArguments r = parseRead(a, xreadgroup);
            if (r.blockMillis() < 0) {
                return null;
            }
            int n = r.streamCount();
            List<String> keys = new ArrayList<>(n);
            List<String> rewritten = new ArrayList<>(command);
            StreamId[] after = new StreamId[n];
            for (int i = 0; i < n; i++) {
                String key = r.key(a, i);
                String token = r.id(a, i);
                keys.add(key);
                if (xreadgroup) {
                    if (!token.equals(">")) {
                        return null; // reading one's own history never waits
                    }
                } else {
                    after[i] = store.<StreamId>read(key, ValueType.STREAM, o -> readFrom(token, (Stream) o));
                    if (token.equals("$") || token.equals("+")) {
                        rewritten.set(1 + r.streamsIndex() + n + i, after[i].toString());
                    }
                }
            }
            BooleanSupplier worthTrying = xreadgroup
                    ? () -> anyGroupHasNews(keys, r.group())
                    : () -> anyStreamHasNews(keys, after);
            return new BlockingRequest(keys, r.blockMillis(), rewritten, worthTrying);
        } catch (RuntimeException e) {
            return null; // the command reports what is wrong with it
        }
    }

    /** Whether any of the streams has an entry after the given id; {@code true} too if a key is not what it should be. */
    private boolean anyStreamHasNews(List<String> keys, StreamId[] after) {
        for (int i = 0; i < keys.size(); i++) {
            StreamId from = after[i];
            try {
                if (store.<Boolean>read(keys.get(i), ValueType.STREAM, o -> {
                    StreamId last = o == null ? null : ((Stream) o).lastEntryId();
                    return last != null && last.compareTo(from) > 0;
                })) {
                    return true;
                }
            } catch (WrongTypeException e) {
                return true; // the command will say so
            }
        }
        return false;
    }

    /**
     * Whether a {@code >} read would find something in any stream - or fail (the key or the group is gone, the key is
     * another type), which the command should be left to report.
     */
    private boolean anyGroupHasNews(List<String> keys, String groupName) {
        for (String key : keys) {
            try {
                if (store.<Boolean>read(key, ValueType.STREAM, o -> {
                    if (o == null) {
                        return true;
                    }
                    ConsumerGroup group = ((Stream) o).group(groupName);
                    return group == null || ((Stream) o).hasUndelivered(group);
                })) {
                    return true;
                }
            } catch (WrongTypeException e) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------- XACK

    /** {@code XACK key group id [id ...]}: the entries are done with. Returns how many were pending. */
    private RespValue xack(List<String> a) {
        Long acknowledged = store.<Long>mutate(a.get(0), ValueType.STREAM, null, o -> {
            Stream stream = (Stream) o;
            ConsumerGroup group = stream.group(a.get(1));
            if (group == null) {
                return 0L;
            }
            List<StreamId> ids = new ArrayList<>(a.size() - 2);
            for (String text : a.subList(2, a.size())) {
                ids.add(exactId(text)); // all or nothing
            }
            return stream.ack(group, ids);
        });
        return new IntegerValue(acknowledged == null ? 0 : acknowledged);
    }

    // ---------------------------------------------------------------- XPENDING

    /**
     * {@code XPENDING key group} (a summary) or {@code XPENDING key group [IDLE ms] start end count [consumer]} (the
     * entries). The arguments are read first, the key and group second, as in Redis.
     */
    private RespValue xpending(List<String> a) {
        boolean summary = a.size() == 2;
        if (!summary && (a.size() < 5 || a.size() > 8)) {
            return SYNTAX;
        }
        String key = a.get(0);
        String groupName = a.get(1);
        long minIdle = 0;
        long count = 0;
        StreamId start = StreamId.ZERO;
        StreamId end = StreamId.MAX;
        String only = null;
        if (!summary) {
            int at = 2;
            if (is(a.get(2), "IDLE")) {
                minIdle = number(a.get(3));
                if (a.size() < 7) {
                    return SYNTAX;
                }
                at += 2;
            }
            count = number(a.get(at + 2));
            if (count < 0) {
                count = 0;
            }
            start = StreamSupport.rangeStart(a.get(at));
            end = StreamSupport.rangeEnd(a.get(at + 1));
            if (at + 3 < a.size()) {
                only = a.get(at + 3);
            }
        }
        long idleAtLeast = minIdle;
        long limit = count;
        StreamId from = start;
        StreamId to = end;
        String consumerName = only;
        long now = System.currentTimeMillis();

        return store.<RespValue>read(key, ValueType.STREAM, o -> {
            ConsumerGroup group = o == null ? null : ((Stream) o).group(groupName);
            if (group == null) {
                return StreamSupport.noGroup(key, groupName);
            }
            Stream stream = (Stream) o;
            if (summary) {
                Stream.PendingSummary info = stream.pendingSummary(group);
                if (info.total() == 0) {
                    return new ArrayValue(List.of(integer(0), new NullValue(), new NullValue(), new NullArray()));
                }
                List<RespValue> consumers = new ArrayList<>();
                for (var held : info.perConsumer()) {
                    consumers.add(new ArrayValue(List.of(bulk(held.getKey()), bulk(Long.toString(held.getValue())))));
                }
                return new ArrayValue(List.of(integer(info.total()), id(info.first()), id(info.last()),
                        new ArrayValue(consumers)));
            }
            Consumer only1 = null;
            if (consumerName != null) {
                only1 = group.consumer(consumerName);
                if (only1 == null) {
                    return ArrayValue.empty();
                }
            }
            List<RespValue> out = new ArrayList<>();
            for (Stream.PendingItem item : stream.pendingRange(group, only1, from, to, limit, idleAtLeast, now)) {
                out.add(new ArrayValue(List.of(id(item.id()), bulk(item.consumer()), integer(item.idle()),
                        integer(item.deliveryCount()))));
            }
            return new ArrayValue(out);
        });
    }

    // ------------------------------------------------------------------ XCLAIM

    /**
     * {@code XCLAIM key group consumer min-idle-time id [id ...] [IDLE ms] [TIME ms] [RETRYCOUNT n] [FORCE] [JUSTID]
     * [LASTID id]}: takes entries over for {@code consumer} if they have been pending for at least {@code min-idle-time}.
     */
    private RespValue xclaim(List<String> a) {
        String key = a.get(0);
        String groupName = a.get(1);
        String consumerName = a.get(2);
        long now = System.currentTimeMillis();
        RespValue reply = store.<RespValue>mutate(key, ValueType.STREAM, null, o -> {
            Stream stream = (Stream) o;
            ConsumerGroup group = stream.group(groupName);
            if (group == null) {
                return StreamSupport.noGroup(key, groupName);
            }
            long minIdle = Math.max(0, number(a.get(3), "Invalid min-idle-time argument for XCLAIM"));

            int next = 4;
            List<StreamId> ids = new ArrayList<>();
            for (; next < a.size(); next++) {
                StreamId id = StreamId.parseExact(a.get(next));
                if (id == null) {
                    break; // the rest are options
                }
                ids.add(id);
            }
            long deliveryTime = -1;
            long retryCount = -1;
            boolean force = false;
            boolean justId = false;
            StreamId lastId = StreamId.ZERO;
            for (; next < a.size(); next++) {
                int more = a.size() - 1 - next;
                String option = a.get(next);
                if (is(option, "FORCE")) {
                    force = true;
                } else if (is(option, "JUSTID")) {
                    justId = true;
                } else if (is(option, "IDLE") && more > 0) {
                    deliveryTime = now - number(a.get(++next), "Invalid IDLE option argument for XCLAIM");
                } else if (is(option, "TIME") && more > 0) {
                    deliveryTime = number(a.get(++next), "Invalid TIME option argument for XCLAIM");
                } else if (is(option, "RETRYCOUNT") && more > 0) {
                    retryCount = number(a.get(++next), "Invalid RETRYCOUNT option argument for XCLAIM");
                } else if (is(option, "LASTID") && more > 0) {
                    lastId = exactId(a.get(++next));
                } else {
                    throw new CommandException("Unrecognized XCLAIM option '" + option + "'");
                }
            }

            Stream.ClaimResult result = stream.claim(group, consumerName, ids,
                    new Stream.ClaimOptions(minIdle, deliveryTime, retryCount, force, justId, lastId), now);

            List<List<String>> effects = new ArrayList<>();
            if (result.consumerCreated()) {
                effects.add(List.of("XGROUP", "CREATECONSUMER", key, groupName, consumerName));
            }
            List<RespValue> claimed = new ArrayList<>(result.claimed().size());
            for (Stream.Claimed c : result.claimed()) {
                claimed.add(c.entry() == null ? id(c.id()) : StreamSupport.entry(c.entry()));
                effects.add(claimEffect(key, groupName, consumerName, c.id(), c.deliveryTime(), c.deliveryCount(),
                        true, null));
            }
            for (StreamId dropped : result.dropped()) {
                effects.add(List.of("XACK", key, groupName, dropped.toString()));
            }
            if (result.positionMoved()) {
                effects.add(setIdEffect(key, groupName, group.lastId(), group.entriesRead()));
            }
            Propagation.instead(effects);
            return new ArrayValue(claimed);
        });
        return reply == null ? StreamSupport.noGroup(key, groupName) : reply;
    }

    // ---------------------------------------------------------------- XRESTORE

    /**
     * {@code XRESTORE key group consumer id delivery-time delivery-count}: internal and admin only, what a snapshot (AOF
     * rewrite, full sync) uses to put a pending entry back. See {@link Stream#restorePending}.
     */
    private RespValue xrestore(List<String> a) {
        StreamId id = exactId(a.get(3));
        long time = number(a.get(4));
        long count = number(a.get(5));
        if (time < 0 || count < 0) {
            throw new CommandException("invalid XRESTORE argument");
        }
        long now = System.currentTimeMillis();
        RespValue reply = store.<RespValue>mutate(a.get(0), ValueType.STREAM, null, o -> {
            Stream stream = (Stream) o;
            ConsumerGroup group = stream.group(a.get(1));
            if (group == null) {
                return StreamSupport.noGroup(a.get(0), a.get(1));
            }
            stream.restorePending(group, a.get(2), id, time, count, now);
            return new SimpleString("OK");
        });
        return reply == null ? StreamSupport.noGroup(a.get(0), a.get(1)) : reply;
    }

    // -------------------------------------------------------------- XAUTOCLAIM

    /**
     * {@code XAUTOCLAIM key group consumer min-idle-time start [COUNT n] [JUSTID]}: claims the entries that have been
     * pending for at least {@code min-idle-time}, scanning from {@code start}; replies with where to continue, the
     * entries, and the ids of pending entries that no longer exist (those are removed).
     */
    private RespValue xautoclaim(List<String> a) {
        String key = a.get(0);
        String groupName = a.get(1);
        String consumerName = a.get(2);
        long minIdle = Math.max(0, number(a.get(3), "Invalid min-idle-time argument for XAUTOCLAIM"));
        StreamId start = StreamSupport.rangeStart(a.get(4));
        long count = AUTOCLAIM_DEFAULT_COUNT;
        boolean justId = false;
        for (int i = 5; i < a.size(); i++) {
            int more = a.size() - 1 - i;
            if (is(a.get(i), "COUNT") && more > 0) {
                try {
                    count = Long.parseLong(a.get(++i));
                } catch (NumberFormatException e) {
                    count = 0;
                }
                if (count < 1 || count > Long.MAX_VALUE / 16) {
                    throw new CommandException("COUNT must be > 0");
                }
            } else if (is(a.get(i), "JUSTID")) {
                justId = true;
            } else {
                throw new CommandException(SYNTAX);
            }
        }
        long limit = count;
        boolean idsOnly = justId;
        long now = System.currentTimeMillis();

        RespValue reply = store.<RespValue>mutate(key, ValueType.STREAM, null, o -> {
            Stream stream = (Stream) o;
            ConsumerGroup group = stream.group(groupName);
            if (group == null) {
                return StreamSupport.noGroup(key, groupName);
            }
            Stream.AutoClaimResult result = stream.autoClaim(group, consumerName, minIdle, start, limit, idsOnly, now);

            List<List<String>> effects = new ArrayList<>();
            if (result.consumerCreated()) {
                effects.add(List.of("XGROUP", "CREATECONSUMER", key, groupName, consumerName));
            }
            List<RespValue> claimed = new ArrayList<>(result.claimed().size());
            for (Stream.Claimed c : result.claimed()) {
                claimed.add(c.entry() == null ? id(c.id()) : StreamSupport.entry(c.entry()));
                effects.add(claimEffect(key, groupName, consumerName, c.id(), c.deliveryTime(), c.deliveryCount(),
                        true, null));
            }
            List<RespValue> deleted = new ArrayList<>(result.deleted().size());
            for (StreamId gone : result.deleted()) {
                deleted.add(id(gone));
                effects.add(List.of("XACK", key, groupName, gone.toString()));
            }
            Propagation.instead(effects);
            return new ArrayValue(List.of(id(result.next()), new ArrayValue(claimed), new ArrayValue(deleted)));
        });
        return reply == null ? StreamSupport.noGroup(key, groupName) : reply;
    }
}
