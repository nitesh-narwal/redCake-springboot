package me.niteshh.redcake.command.commands.types;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandProvider;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.ArrayValue;
import me.niteshh.redcake.resp.IntegerValue;
import me.niteshh.redcake.store.KeyValueStore;
import me.niteshh.redcake.store.ValueType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static me.niteshh.redcake.command.LambdaCommand.read;
import static me.niteshh.redcake.command.LambdaCommand.shrink;
import static me.niteshh.redcake.command.LambdaCommand.write;

/**
 * Set commands: {@code SADD SREM SMEMBERS SISMEMBER SCARD SINTER SUNION SDIFF}.
 * A set is a {@code LinkedHashSet<String>}. Random-element commands
 * ({@code SPOP}, {@code SRANDMEMBER}) are intentionally absent: they are
 * non-deterministic and would make replicas diverge unless rewritten as
 * explicit {@code SREM}s.
 */
@Component
@RequiredArgsConstructor
public class SetCommands implements CommandProvider {

    private final KeyValueStore store;

    @Override
    public List<RedCakeCommand> commands() {
        return List.of(
                write("SADD", 2, -1, a -> new IntegerValue(store.<Integer>mutate(
                        a.get(0), ValueType.SET, LinkedHashSet::new, o -> {
                            int added = 0;
                            for (String member : a.subList(1, a.size())) {
                                if (set(o).add(member)) {
                                    added++;
                                }
                            }
                            return added;
                        }))),
                shrink("SREM", 2, -1, a -> {
                    Integer removed = store.<Integer>mutate(a.get(0), ValueType.SET, null, o -> {
                        int count = 0;
                        for (String member : a.subList(1, a.size())) {
                            if (set(o).remove(member)) {
                                count++;
                            }
                        }
                        return count;
                    });
                    return new IntegerValue(removed == null ? 0 : removed);
                }),
                read("SMEMBERS", 1, 1, a -> ArrayValue.ofBulkStrings(members(a.get(0)))),
                read("SISMEMBER", 2, 2, a -> new IntegerValue(readSet(a.get(0),
                        s -> s != null && s.contains(a.get(1)) ? 1 : 0))),
                read("SCARD", 1, 1, a -> new IntegerValue(readSet(a.get(0), s -> s == null ? 0 : s.size()))),
                read("SINTER", 1, -1, a -> {
                    List<String> result = members(a.getFirst());
                    for (String key : a.subList(1, a.size())) {
                        result.retainAll(new LinkedHashSet<>(members(key)));
                    }
                    return ArrayValue.ofBulkStrings(result);
                }),
                read("SUNION", 1, -1, a -> {
                    Set<String> result = new LinkedHashSet<>();
                    for (String key : a) {
                        result.addAll(members(key));
                    }
                    return ArrayValue.ofBulkStrings(new ArrayList<>(result));
                }),
                read("SDIFF", 1, -1, a -> {
                    List<String> result = members(a.getFirst());
                    for (String key : a.subList(1, a.size())) {
                        result.removeAll(new LinkedHashSet<>(members(key)));
                    }
                    return ArrayValue.ofBulkStrings(result);
                })
        );
    }

    /** @return a private copy of the members (empty list if the key is absent) */
    private List<String> members(String key) {
        return readSet(key, s -> s == null ? new ArrayList<String>() : new ArrayList<>(s));
    }

    private <T> T readSet(String key, Function<Set<String>, T> fn) {
        return store.read(key, ValueType.SET, o -> fn.apply(set(o)));
    }

    @SuppressWarnings("unchecked")
    private static Set<String> set(Object o) {
        return (Set<String>) o;
    }
}
