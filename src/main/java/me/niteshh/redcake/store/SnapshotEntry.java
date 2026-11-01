package me.niteshh.redcake.store;

import me.niteshh.redcake.json.JsonDocument;
import me.niteshh.redcake.stream.Stream;
import me.niteshh.redcake.vector.VectorSet;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * One live key captured for a replication snapshot or an AOF rewrite.
 * {@code value} is an immutable copy (see {@code forEachSnapshot}), so it can
 * be serialized after the write lock has been released.
 *
 * <p>{@link #toCommands()} is the single place that knows how to turn any
 * value type back into write commands; replication and the AOF both use it.
 *
 * @param key       the key
 * @param value     the value (String or a private copy of a collection)
 * @param expiresAt absolute expiry in epoch milliseconds, or {@code null}
 */
public record SnapshotEntry(
        String key,
        Object value,
        Long expiresAt
) {

    /**
     * Collections are written in chunks of this many elements so no single
     * command exceeds the parser's argument limit.
     */
    static final int CHUNK = 256;

    /**
     * The same commands as {@link #toCommands()}, produced one at a time. A vector set is dumped into many
     * chunks of up to half a megabyte; creating their commands lazily keeps the sender from holding a second copy
     * of the whole index in memory while it streams them.
     */
    public Iterable<List<String>> commands() {
        if (value instanceof VectorSet.Dump dump) {
            return () -> vectorCommands(dump);
        }
        if (value instanceof JsonDocument.Dump dump) {
            return () -> jsonCommands(dump);
        }
        if (value instanceof Stream stream) {
            return () -> streamCommands(stream);
        }
        return toCommands();
    }

    /**
     * Commands that recreate this key, including its (absolute) expiry.
     * Strings become one {@code SET ... PXAT}; collections become
     * {@code HSET/RPUSH/SADD/ZADD} chunks followed by {@code PEXPIREAT}.
     */
    public List<List<String>> toCommands() {
        List<List<String>> commands = new ArrayList<>();

        switch (value) {
            case String string -> {
                if (expiresAt == null) {
                    commands.add(List.of("SET", key, string));
                } else {
                    commands.add(List.of("SET", key, string, "PXAT", Long.toString(expiresAt)));
                }
                return commands;
            }
            case Map<?, ?> map -> {
                List<String> chunk = start("HSET");
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    chunk.add((String) e.getKey());
                    chunk.add((String) e.getValue());
                    if (chunk.size() >= 2 + CHUNK * 2) {
                        commands.add(chunk);
                        chunk = start("HSET");
                    }
                }
                flush(commands, chunk);
            }
            case List<?> list -> {
                List<String> chunk = start("RPUSH");
                for (Object element : list) {
                    chunk.add((String) element);
                    if (chunk.size() >= 2 + CHUNK) {
                        commands.add(chunk);
                        chunk = start("RPUSH");
                    }
                }
                flush(commands, chunk);
            }
            case Set<?> set -> {
                List<String> chunk = start("SADD");
                for (Object member : set) {
                    chunk.add((String) member);
                    if (chunk.size() >= 2 + CHUNK) {
                        commands.add(chunk);
                        chunk = start("SADD");
                    }
                }
                flush(commands, chunk);
            }
            case ZSet zset -> {
                List<String> chunk = start("ZADD");
                for (ZSet.Entry e : zset.toList()) {
                    chunk.add(ZSet.formatScore(e.score()));
                    chunk.add(e.member());
                    if (chunk.size() >= 2 + CHUNK * 2) {
                        commands.add(chunk);
                        chunk = start("ZADD");
                    }
                }
                flush(commands, chunk);
            }
            case VectorSet.Dump dump -> {
                for (List<String> command : commands()) {
                    commands.add(command);
                }
                return commands; // the iterator already ends with PEXPIREAT
            }
            case JsonDocument.Dump dump -> {
                for (List<String> command : commands()) {
                    commands.add(command);
                }
                return commands; // likewise
            }
            case Stream stream -> {
                for (List<String> command : commands()) {
                    commands.add(command);
                }
                return commands; // likewise
            }
            default -> throw new IllegalStateException("Unsupported value: " + value.getClass());
        }

        if (expiresAt != null) {
            commands.add(List.of("PEXPIREAT", key, Long.toString(expiresAt)));
        }
        return commands;
    }

    /**
     * {@code VRESTORE key HEADER <bytes>}, one {@code VRESTORE key NODES <first id> <bytes>} per chunk,
     * {@code VRESTORE key END}, then the expiry. Binary data travels as ISO-8859-1 text, like every value.
     */
    private Iterator<List<String>> vectorCommands(VectorSet.Dump dump) {
        int chunks = dump.chunks().size();
        int total = chunks + 2 + (expiresAt != null ? 1 : 0);
        return new Iterator<>() {
            private int index;

            @Override
            public boolean hasNext() {
                return index < total;
            }

            @Override
            public List<String> next() {
                if (index >= total) {
                    throw new NoSuchElementException();
                }
                int i = index++;
                if (i == 0) {
                    return List.of("VRESTORE", key, "HEADER", new String(dump.header(), StandardCharsets.ISO_8859_1));
                }
                if (i <= chunks) {
                    VectorSet.Chunk chunk = dump.chunks().get(i - 1);
                    return List.of("VRESTORE", key, "NODES", Integer.toString(chunk.firstId()),
                            new String(chunk.data(), StandardCharsets.ISO_8859_1));
                }
                if (i == chunks + 1) {
                    return List.of("VRESTORE", key, "END");
                }
                return List.of("PEXPIREAT", key, Long.toString(expiresAt));
            }
        };
    }

    /**
     * The steps of a JSON document ({@code JSON.SET key $ ...}, then {@code JSON.ARRAPPEND} / {@code JSON.SET} pieces
     * when it is too large for one command), each with the key put after the command name, then the expiry.
     */
    private Iterator<List<String>> jsonCommands(JsonDocument.Dump dump) {
        List<List<String>> steps = dump.steps();
        int total = steps.size() + (expiresAt != null ? 1 : 0);
        return new Iterator<>() {
            private int index;

            @Override
            public boolean hasNext() {
                return index < total;
            }

            @Override
            public List<String> next() {
                if (index >= total) {
                    throw new NoSuchElementException();
                }
                int i = index++;
                if (i == steps.size()) {
                    return List.of("PEXPIREAT", key, Long.toString(expiresAt));
                }
                List<String> step = steps.get(i);
                List<String> command = new ArrayList<>(step.size() + 1);
                command.add(step.get(0));
                command.add(key);
                command.addAll(step.subList(1, step.size()));
                return command;
            }
        };
    }

    /**
     * The commands that rebuild a stream ({@link Stream#snapshotCommands}: {@code XADD} per entry, {@code XSETID},
     * the groups with their consumers and pending entries), then the expiry. Created one at a time: a stream can hold
     * millions of entries and the sender should not hold them as commands as well.
     */
    private Iterator<List<String>> streamCommands(Stream stream) {
        Iterator<List<String>> rebuild = stream.snapshotCommands(key);
        return new Iterator<>() {
            private boolean expiryDone = expiresAt == null;

            @Override
            public boolean hasNext() {
                return rebuild.hasNext() || !expiryDone;
            }

            @Override
            public List<String> next() {
                if (rebuild.hasNext()) {
                    return rebuild.next();
                }
                if (expiryDone) {
                    throw new NoSuchElementException();
                }
                expiryDone = true;
                return List.of("PEXPIREAT", key, Long.toString(expiresAt));
            }
        };
    }

    private List<String> start(String command) {
        List<String> words = new ArrayList<>();
        words.add(command);
        words.add(key);
        return words;
    }

    /** Adds {@code chunk} if it holds elements beyond "COMMAND key". */
    private static void flush(List<List<String>> commands, List<String> chunk) {
        if (chunk.size() > 2) {
            commands.add(chunk);
        }
    }
}
