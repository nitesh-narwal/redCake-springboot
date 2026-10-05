package me.niteshh.redcake.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
            default -> throw new IllegalStateException("Unsupported value: " + value.getClass());
        }

        if (expiresAt != null) {
            commands.add(List.of("PEXPIREAT", key, Long.toString(expiresAt)));
        }
        return commands;
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
