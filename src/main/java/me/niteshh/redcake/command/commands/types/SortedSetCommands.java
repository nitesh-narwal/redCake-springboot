package me.niteshh.redcake.command.commands.types;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandException;
import me.niteshh.redcake.command.CommandProvider;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.store.KeyValueStore;
import me.niteshh.redcake.store.ValueType;
import me.niteshh.redcake.store.ZSet;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import static me.niteshh.redcake.command.LambdaCommand.read;
import static me.niteshh.redcake.command.LambdaCommand.shrink;
import static me.niteshh.redcake.command.LambdaCommand.write;

/**
 * Sorted-set commands: {@code ZADD ZREM ZCARD ZSCORE ZRANK ZINCRBY ZRANGE
 * ZREVRANGE ZRANGEBYSCORE ZCOUNT ZPOPMIN ZPOPMAX}. See {@link ZSet} for the
 * data structure.
 */
@Component
@RequiredArgsConstructor
public class SortedSetCommands implements CommandProvider {

    private final KeyValueStore store;

    @Override
    public List<RedCakeCommand> commands() {
        return List.of(
                write("ZADD", 3, -1, this::zadd),
                shrink("ZREM", 2, -1, a -> {
                    Integer removed = store.<Integer>mutate(a.get(0), ValueType.ZSET, null, o -> {
                        int count = 0;
                        for (String member : a.subList(1, a.size())) {
                            if (zset(o).remove(member)) {
                                count++;
                            }
                        }
                        return count;
                    });
                    return new IntegerValue(removed == null ? 0 : removed);
                }),
                read("ZCARD", 1, 1, a -> new IntegerValue(readZ(a.get(0), z -> z == null ? 0 : z.size()))),
                read("ZSCORE", 2, 2, a -> {
                    Double score = readZ(a.get(0), z -> z == null ? null : z.score(a.get(1)));
                    return score == null ? new NullValue() : new BulkString(ZSet.formatScore(score));
                }),
                read("ZRANK", 2, 2, a -> {
                    int rank = readZ(a.get(0), z -> z == null ? -1 : z.rank(a.get(1)));
                    return rank < 0 ? new NullValue() : new IntegerValue(rank);
                }),
                write("ZINCRBY", 3, 3, a -> {
                    double amount = CommandSupport.parseDouble(a.get(1));
                    double result = store.<Double>mutate(a.get(0), ValueType.ZSET, ZSet::new, o -> {
                        Double current = zset(o).score(a.get(2));
                        double updated = (current == null ? 0 : current) + amount;
                        if (Double.isNaN(updated)) {
                            throw new CommandException("resulting score is not a number (NaN)");
                        }
                        zset(o).put(a.get(2), updated);
                        return updated;
                    });
                    return new BulkString(ZSet.formatScore(result));
                }),
                read("ZRANGE", 3, 4, a -> rangeByIndex(a, false)),
                read("ZREVRANGE", 3, 4, a -> rangeByIndex(a, true)),
                read("ZRANGEBYSCORE", 3, 7, this::rangeByScore),
                read("ZCOUNT", 3, 3, a -> {
                    Bound min = Bound.parse(a.get(1));
                    Bound max = Bound.parse(a.get(2));
                    return new IntegerValue(readZ(a.get(0), z -> z == null ? 0
                            : z.toList().stream().filter(e -> min.below(e.score()) && max.above(e.score())).count()));
                }),
                shrink("ZPOPMIN", 1, 2, a -> pop(a, true)),
                shrink("ZPOPMAX", 1, 2, a -> pop(a, false))
        );
    }

    /** {@code ZADD key [NX|XX] [CH] score member [score member ...]}. */
    private RespValue zadd(List<String> a) {
        boolean nx = false;
        boolean xx = false;
        boolean ch = false;
        int i = 1;
        while (i < a.size()) {
            String option = a.get(i).toUpperCase(Locale.ROOT);
            if ("NX".equals(option)) {
                nx = true;
            } else if ("XX".equals(option)) {
                xx = true;
            } else if ("CH".equals(option)) {
                ch = true;
            } else {
                break; // first non-option word is the first score
            }
            i++;
        }
        if (nx && xx) {
            return new ErrorValue("XX and NX options at the same time are not compatible");
        }
        int pairs = a.size() - i;
        if (pairs < 2 || pairs % 2 != 0) {
            return CommandSupport.SYNTAX_ERROR;
        }

        // Parse every score first: the key must not change if one is invalid.
        List<Double> scores = new ArrayList<>();
        for (int j = i; j < a.size(); j += 2) {
            scores.add(CommandSupport.parseDouble(a.get(j)));
        }

        final boolean onlyNew = nx;
        final boolean onlyExisting = xx;
        final boolean countChanged = ch;
        final int first = i;
        int result = store.<Integer>mutate(a.get(0), ValueType.ZSET, ZSet::new, o -> {
            int count = 0;
            for (int j = 0; j < scores.size(); j++) {
                String member = a.get(first + 2 * j + 1);
                Double current = zset(o).score(member);
                if ((onlyNew && current != null) || (onlyExisting && current == null)) {
                    continue;
                }
                double score = scores.get(j);
                boolean added = zset(o).put(member, score);
                if (added || (countChanged && (current == null || current != score))) {
                    count++;
                }
            }
            return count;
        });
        return new IntegerValue(result);
    }

    private RespValue rangeByIndex(List<String> a, boolean reverse) {
        long start = CommandSupport.parseLong(a.get(1));
        long stop = CommandSupport.parseLong(a.get(2));
        boolean withScores = false;
        if (a.size() == 4) {
            if (!"WITHSCORES".equalsIgnoreCase(a.get(3))) {
                return CommandSupport.SYNTAX_ERROR;
            }
            withScores = true;
        }
        final boolean scores = withScores;

        return readZ(a.get(0), z -> {
            if (z == null) {
                return ArrayValue.empty();
            }
            List<ZSet.Entry> all = z.toList();
            if (reverse) {
                Collections.reverse(all);
            }
            int[] r = CommandSupport.range(start, stop, all.size());
            return r == null ? ArrayValue.empty() : flatten(all.subList(r[0], r[1] + 1), scores);
        });
    }

    /** {@code ZRANGEBYSCORE key min max [WITHSCORES] [LIMIT offset count]}. */
    private RespValue rangeByScore(List<String> a) {
        Bound min = Bound.parse(a.get(1));
        Bound max = Bound.parse(a.get(2));
        boolean withScores = false;
        long offset = 0;
        long count = Long.MAX_VALUE;

        for (int i = 3; i < a.size(); i++) {
            String option = a.get(i).toUpperCase(Locale.ROOT);
            if ("WITHSCORES".equals(option)) {
                withScores = true;
            } else if ("LIMIT".equals(option) && i + 2 < a.size()) {
                offset = CommandSupport.parseLong(a.get(++i));
                long requested = CommandSupport.parseLong(a.get(++i));
                count = requested < 0 ? Long.MAX_VALUE : requested;
            } else {
                return CommandSupport.SYNTAX_ERROR;
            }
        }
        final boolean scores = withScores;
        final long skip = offset;
        final long limit = count;

        return readZ(a.get(0), z -> {
            if (z == null) {
                return ArrayValue.empty();
            }
            List<ZSet.Entry> matches = z.toList().stream()
                    .filter(e -> min.below(e.score()) && max.above(e.score()))
                    .skip(skip)
                    .limit(limit)
                    .toList();
            return flatten(matches, scores);
        });
    }

    /** {@code ZPOPMIN/ZPOPMAX key [count]}: pops up to count members as [member, score, ...]. */
    private RespValue pop(List<String> a, boolean min) {
        long count = a.size() == 2 ? CommandSupport.parseLong(a.get(1)) : 1;
        if (count < 0) {
            return new ErrorValue("value is out of range, must be positive");
        }
        List<ZSet.Entry> popped = store.<List<ZSet.Entry>>mutate(a.get(0), ValueType.ZSET, null, o -> {
            List<ZSet.Entry> out = new ArrayList<>();
            for (long i = 0; i < count && zset(o).size() > 0; i++) {
                out.add(min ? zset(o).pollFirst() : zset(o).pollLast());
            }
            return out;
        });
        return popped == null ? ArrayValue.empty() : flatten(popped, true);
    }

    private static ArrayValue flatten(List<ZSet.Entry> entries, boolean withScores) {
        List<RespValue> out = new ArrayList<>();
        for (ZSet.Entry e : entries) {
            out.add(new BulkString(e.member()));
            if (withScores) {
                out.add(new BulkString(ZSet.formatScore(e.score())));
            }
        }
        return new ArrayValue(out);
    }

    /** One end of a score interval: {@code 5}, {@code (5} (exclusive), {@code -inf}, {@code +inf}. */
    private record Bound(double value, boolean exclusive) {

        static Bound parse(String text) {
            boolean exclusive = text.startsWith("(");
            return new Bound(CommandSupport.parseDouble(exclusive ? text.substring(1) : text), exclusive);
        }

        /** @return {@code true} if {@code score} is at or above this lower bound */
        boolean below(double score) {
            return exclusive ? score > value : score >= value;
        }

        /** @return {@code true} if {@code score} is at or below this upper bound */
        boolean above(double score) {
            return exclusive ? score < value : score <= value;
        }
    }

    private <T> T readZ(String key, Function<ZSet, T> fn) {
        return store.read(key, ValueType.ZSET, o -> fn.apply(zset(o)));
    }

    private static ZSet zset(Object o) {
        return (ZSet) o;
    }
}
