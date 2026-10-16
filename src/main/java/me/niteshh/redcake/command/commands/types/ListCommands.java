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
import java.util.Collections;
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
                        if (r == null) {
                            l.clear();
                        } else {
                            l.subList(r[1] + 1, l.size()).clear(); // tail first so r[0] stays valid
                            l.subList(0, r[0]).clear();
                        }
                        return true;
                    });
                    return new SimpleString("OK");
                })
        );
    }

    private int push(List<String> a, boolean head) {
        return store.<Integer>mutate(a.get(0), ValueType.LIST, ArrayList::new, o -> {
            List<String> l = list(o);
            List<String> values = new ArrayList<>(a.subList(1, a.size()));
            if (head) {
                // LPUSH a b c leaves c b a: one bulk insert instead of one memmove per value.
                Collections.reverse(values);
                l.addAll(0, values);
            } else {
                l.addAll(values);
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
            int n = (int) Math.min(count, l.size());
            List<String> window = head ? l.subList(0, n) : l.subList(l.size() - n, l.size());
            List<String> out = new ArrayList<>(window);
            if (!head) {
                Collections.reverse(out); // RPOP returns the last element first
            }
            window.clear(); // one memmove instead of one per popped element
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
        long limit = count == 0 ? Long.MAX_VALUE : Math.abs(count);
        boolean fromTail = count < 0;
        List<String> kept = new ArrayList<>(l.size());
        int removed = 0;
        // Single pass that rebuilds the list; removing in place shifts the tail once per hit (O(n^2)).
        for (int i = 0; i < l.size(); i++) {
            String value = l.get(fromTail ? l.size() - 1 - i : i);
            if (removed < limit && value.equals(element)) {
                removed++;
            } else {
                kept.add(value);
            }
        }
        if (removed > 0) {
            if (fromTail) {
                Collections.reverse(kept);
            }
            l.clear();
            l.addAll(kept);
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
