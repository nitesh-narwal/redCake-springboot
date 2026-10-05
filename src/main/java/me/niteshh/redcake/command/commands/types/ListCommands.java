package me.niteshh.redcake.command.commands.types;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandException;
import me.niteshh.redcake.command.CommandProvider;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.store.KeyValueStore;
import me.niteshh.redcake.store.ValueType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static me.niteshh.redcake.command.LambdaCommand.read;
import static me.niteshh.redcake.command.LambdaCommand.shrink;
import static me.niteshh.redcake.command.LambdaCommand.write;

/**
 * List commands: {@code LPUSH RPUSH LPOP RPOP LLEN LRANGE LINDEX LSET LREM LTRIM}.
 * A list is an {@code ArrayList<String>}; operations at the head are O(n)
 * (a memmove), which is fine for typical sizes. Blocking pops are not supported.
 */
@Component
@RequiredArgsConstructor
public class ListCommands implements CommandProvider {

    private final KeyValueStore store;

    @Override
    public List<RedCakeCommand> commands() {
        return List.of(
                write("LPUSH", 2, -1, a -> new IntegerValue(push(a, true))),
                write("RPUSH", 2, -1, a -> new IntegerValue(push(a, false))),
                shrink("LPOP", 1, 2, a -> pop(a, true)),
                shrink("RPOP", 1, 2, a -> pop(a, false)),
                read("LLEN", 1, 1, a -> new IntegerValue(readList(a.get(0), l -> l == null ? 0 : l.size()))),
                read("LRANGE", 3, 3, a -> {
                    long start = CommandSupport.parseLong(a.get(1));
                    long stop = CommandSupport.parseLong(a.get(2));
                    return readList(a.get(0), l -> {
                        if (l == null) {
                            return ArrayValue.empty();
                        }
                        int[] r = CommandSupport.range(start, stop, l.size());
                        return r == null
                                ? ArrayValue.empty()
                                : ArrayValue.ofBulkStrings(new ArrayList<>(l.subList(r[0], r[1] + 1)));
                    });
                }),
                read("LINDEX", 2, 2, a -> {
                    long index = CommandSupport.parseLong(a.get(1));
                    return readList(a.get(0), l -> {
                        if (l == null) {
                            return new NullValue();
                        }
                        long i = index < 0 ? l.size() + index : index;
                        return i < 0 || i >= l.size() ? new NullValue() : new BulkString(l.get((int) i));
                    });
                }),
                write("LSET", 3, 3, a -> {
                    long index = CommandSupport.parseLong(a.get(1));
                    List<Boolean> done = new ArrayList<>();
                    Boolean exists = store.<Boolean>mutate(a.get(0), ValueType.LIST, null, o -> {
                        List<String> l = list(o);
                        long i = index < 0 ? l.size() + index : index;
                        if (i < 0 || i >= l.size()) {
                            throw new CommandException("index out of range");
                        }
                        l.set((int) i, a.get(2));
                        return true;
                    });
                    return exists == null ? new ErrorValue("no such key") : new SimpleString("OK");
                }),
                shrink("LREM", 3, 3, a -> {
                    long count = CommandSupport.parseLong(a.get(1));
                    Integer removed = store.<Integer>mutate(a.get(0), ValueType.LIST, null,
                            o -> removeOccurrences(list(o), a.get(2), count));
                    return new IntegerValue(removed == null ? 0 : removed);
                }),
                shrink("LTRIM", 3, 3, a -> {
                    long start = CommandSupport.parseLong(a.get(1));
                    long stop = CommandSupport.parseLong(a.get(2));
                    store.<Boolean>mutate(a.get(0), ValueType.LIST, null, o -> {
                        List<String> l = list(o);
                        int[] r = CommandSupport.range(start, stop, l.size());
                        List<String> kept = r == null ? List.of() : new ArrayList<>(l.subList(r[0], r[1] + 1));
                        l.clear();
                        l.addAll(kept);
                        return true;
                    });
                    return new SimpleString("OK");
                })
        );
    }

    private int push(List<String> a, boolean head) {
        return store.<Integer>mutate(a.get(0), ValueType.LIST, ArrayList::new, o -> {
            List<String> l = list(o);
            for (String value : a.subList(1, a.size())) {
                if (head) {
                    l.add(0, value);
                } else {
                    l.add(value);
                }
            }
            return l.size();
        });
    }

    /** {@code LPOP/RPOP key [count]}: one bulk string, or an array when a count is given. */
    private RespValue pop(List<String> a, boolean head) {
        boolean withCount = a.size() == 2;
        long count = withCount ? CommandSupport.parseLong(a.get(1)) : 1;
        if (count < 0) {
            return new ErrorValue("value is out of range, must be positive");
        }

        List<String> popped = store.<List<String>>mutate(a.get(0), ValueType.LIST, null, o -> {
            List<String> l = list(o);
            List<String> out = new ArrayList<>();
            for (long i = 0; i < count && !l.isEmpty(); i++) {
                out.add(l.remove(head ? 0 : l.size() - 1));
            }
            return out;
        });

        if (popped == null) {
            return new NullValue();
        }
        return withCount ? ArrayValue.ofBulkStrings(popped)
                : popped.isEmpty() ? new NullValue() : new BulkString(popped.getFirst());
    }

    /** count &gt; 0: first {@code count} from the head; &lt; 0: from the tail; 0: all. */
    private static int removeOccurrences(List<String> l, String element, long count) {
        int removed = 0;
        long limit = count == 0 ? Long.MAX_VALUE : Math.abs(count);
        if (count >= 0) {
            for (int i = 0; i < l.size() && removed < limit; ) {
                if (l.get(i).equals(element)) {
                    l.remove(i);
                    removed++;
                } else {
                    i++;
                }
            }
        } else {
            for (int i = l.size() - 1; i >= 0 && removed < limit; i--) {
                if (l.get(i).equals(element)) {
                    l.remove(i);
                    removed++;
                }
            }
        }
        return removed;
    }

    private <T> T readList(String key, Function<List<String>, T> fn) {
        return store.read(key, ValueType.LIST, o -> fn.apply(list(o)));
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Object o) {
        return (List<String>) o;
    }
}
