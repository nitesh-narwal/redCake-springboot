package me.niteshh.redcake.command.commands.types;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandException;
import me.niteshh.redcake.command.CommandProvider;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.LambdaCommand;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.store.KeyValueStore;
import me.niteshh.redcake.store.ValueType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static me.niteshh.redcake.command.LambdaCommand.read;
import static me.niteshh.redcake.command.LambdaCommand.shrink;
import static me.niteshh.redcake.command.LambdaCommand.write;

/**
 * Hash commands: {@code HSET HMSET HSETNX HGET HMGET HDEL HEXISTS HGETALL HKEYS
 * HVALS HLEN HINCRBY HSTRLEN}. A hash is a {@code LinkedHashMap<String,String>}
 * (field order = insertion order). Every body runs inside the store's atomic
 * per-key section via {@code read}/{@code mutate}.
 */
@Component
@RequiredArgsConstructor
public class HashCommands implements CommandProvider {

    private final KeyValueStore store;

    @Override
    public List<RedCakeCommand> commands() {
        return List.of(
                write("HSET", 3, -1, a -> {
                    requireFieldValuePairs(a, "hset");
                    return new IntegerValue(setFields(a));
                }),
                write("HMSET", 3, -1, a -> {
                    requireFieldValuePairs(a, "hmset");
                    setFields(a);
                    return new SimpleString("OK");
                }),
                write("HSETNX", 3, 3, a -> new IntegerValue(store.<Integer>mutate(
                        a.get(0), ValueType.HASH, LinkedHashMap::new,
                        o -> map(o).putIfAbsent(a.get(1), a.get(2)) == null ? 1 : 0))),
                read("HGET", 2, 2, a -> bulkOrNull(readHash(a.get(0),
                        m -> m == null ? null : m.get(a.get(1))))),
                read("HMGET", 2, -1, a -> readHash(a.get(0), m -> {
                    List<RespValue> values = new ArrayList<>();
                    for (String field : a.subList(1, a.size())) {
                        values.add(bulkOrNull(m == null ? null : m.get(field)));
                    }
                    return new ArrayValue(values);
                })),
                shrink("HDEL", 2, -1, a -> {
                    Integer removed = store.<Integer>mutate(a.get(0), ValueType.HASH, null, o -> {
                        int count = 0;
                        for (String field : a.subList(1, a.size())) {
                            if (map(o).remove(field) != null) {
                                count++;
                            }
                        }
                        return count;
                    });
                    return new IntegerValue(removed == null ? 0 : removed);
                }),
                read("HEXISTS", 2, 2, a -> new IntegerValue(readHash(a.get(0),
                        m -> m != null && m.containsKey(a.get(1)) ? 1 : 0))),
                read("HGETALL", 1, 1, a -> readHash(a.get(0), m -> {
                    List<RespValue> flat = new ArrayList<>();
                    if (m != null) {
                        m.forEach((field, value) -> {
                            flat.add(new BulkString(field));
                            flat.add(new BulkString(value));
                        });
                    }
                    return new ArrayValue(flat);
                })),
                read("HKEYS", 1, 1, a -> readHash(a.get(0), m ->
                        ArrayValue.ofBulkStrings(m == null ? List.of() : new ArrayList<>(m.keySet())))),
                read("HVALS", 1, 1, a -> readHash(a.get(0), m ->
                        ArrayValue.ofBulkStrings(m == null ? List.of() : new ArrayList<>(m.values())))),
                read("HLEN", 1, 1, a -> new IntegerValue(readHash(a.get(0), m -> m == null ? 0 : m.size()))),
                read("HSTRLEN", 2, 2, a -> new IntegerValue(readHash(a.get(0), m -> {
                    String value = m == null ? null : m.get(a.get(1));
                    return value == null ? 0 : value.length();
                }))),
                write("HINCRBY", 3, 3, a -> {
                    long amount = CommandSupport.parseLong(a.get(2));
                    return new IntegerValue(store.<Long>mutate(
                            a.get(0), ValueType.HASH, LinkedHashMap::new, o -> {
                                String current = map(o).get(a.get(1));
                                long base;
                                try {
                                    base = current == null ? 0 : Long.parseLong(current);
                                } catch (NumberFormatException e) {
                                    throw new CommandException("hash value is not an integer");
                                }
                                if ((amount > 0 && base > Long.MAX_VALUE - amount)
                                        || (amount < 0 && base < Long.MIN_VALUE - amount)) {
                                    throw new CommandException("increment or decrement would overflow");
                                }
                                long updated = base + amount;
                                map(o).put(a.get(1), Long.toString(updated));
                                return updated;
                            }));
                })
        );
    }

    private int setFields(List<String> a) {
        return store.<Integer>mutate(a.get(0), ValueType.HASH, LinkedHashMap::new, o -> {
            int added = 0;
            for (int i = 1; i < a.size(); i += 2) {
                if (map(o).put(a.get(i), a.get(i + 1)) == null) {
                    added++;
                }
            }
            return added;
        });
    }

    private static void requireFieldValuePairs(List<String> a, String name) {
        if (a.size() % 2 == 0) {
            throw new CommandException(CommandSupport.wrongArity(name));
        }
    }

    /** Runs {@code fn} with the hash (or {@code null} if the key is absent). */
    private <T> T readHash(String key, Function<Map<String, String>, T> fn) {
        return store.read(key, ValueType.HASH, o -> fn.apply(map(o)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> map(Object o) {
        return (Map<String, String>) o;
    }

    static RespValue bulkOrNull(String value) {
        return value == null ? new NullValue() : new BulkString(value);
    }
}
