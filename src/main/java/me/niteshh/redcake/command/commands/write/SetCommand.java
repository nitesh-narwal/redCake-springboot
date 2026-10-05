package me.niteshh.redcake.command.commands.write;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.store.*;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code SET key value [NX|XX] [GET] [EX s|PX ms|EXAT s|PXAT ms|KEEPTTL]}.
 *
 * <p>Parsing is shared by {@link #execute} and {@link #normalize}. Normalizing
 * rewrites every expiry flag into a single {@code PXAT <absolute ms>} so
 * replicas/AOF replay the exact same deadline.
 */
@Component
@RequiredArgsConstructor
public class SetCommand implements RedCakeCommand {

    private final KeyValueStore store;

    @Override
    public String name() {
        return "SET";
    }

    @Override
    public boolean isWrite() {
        return true;
    }

    @Override
    public RespValue execute(List<String> arguments) {
        if (arguments.size() < 2) {
            return CommandSupport.wrongArity("set");
        }
        Parsed parsed = parse(arguments, System.currentTimeMillis());
        if (parsed.error != null) {
            return parsed.error;
        }

        SetResult result = store.set(arguments.get(0), arguments.get(1), parsed.options);

        if (parsed.options.returnOld()) {
            return result.previousValue() == null
                    ? new NullValue()
                    : new BulkString(result.previousValue());
        }
        return result.applied() ? new SimpleString("OK") : new NullValue();
    }

    @Override
    public List<String> normalize(List<String> command, long nowMillis) {
        if (command.size() < 3) {
            return command;
        }
        List<String> arguments = command.subList(1, command.size());
        Parsed parsed = parse(arguments, nowMillis);
        if (parsed.error != null || parsed.options.expiresAt() == null) {
            return command; // invalid (execute reports it) or nothing to rewrite
        }

        List<String> rewritten = new java.util.ArrayList<>();
        rewritten.add("SET");
        rewritten.add(arguments.get(0));
        rewritten.add(arguments.get(1));
        SetOptions o = parsed.options;
        if (o.condition() == SetOptions.Condition.ONLY_IF_ABSENT) {
            rewritten.add("NX");
        } else if (o.condition() == SetOptions.Condition.ONLY_IF_PRESENT) {
            rewritten.add("XX");
        }
        if (o.returnOld()) {
            rewritten.add("GET");
        }
        rewritten.add("PXAT");
        rewritten.add(Long.toString(o.expiresAt()));
        return rewritten;
    }

    /** Result of option parsing: either valid options or an error reply. */
    private record Parsed(SetOptions options, ErrorValue error) {
    }

    /** Parses everything after {@code key value}; relative times are resolved against {@code now}. */
    private Parsed parse(List<String> arguments, long now) {
        SetOptions.Condition condition = SetOptions.Condition.ALWAYS;
        boolean get = false;
        boolean keepTtl = false;
        Long expiresAt = null;

        for (int i = 2; i < arguments.size(); i++) {
            String option = arguments.get(i).toUpperCase(java.util.Locale.ROOT);
            switch (option) {
                case "NX" -> {
                    if (condition == SetOptions.Condition.ONLY_IF_PRESENT) {
                        return error(CommandSupport.SYNTAX_ERROR);
                    }
                    condition = SetOptions.Condition.ONLY_IF_ABSENT;
                }
                case "XX" -> {
                    if (condition == SetOptions.Condition.ONLY_IF_ABSENT) {
                        return error(CommandSupport.SYNTAX_ERROR);
                    }
                    condition = SetOptions.Condition.ONLY_IF_PRESENT;
                }
                case "GET" -> get = true;
                case "KEEPTTL" -> {
                    if (expiresAt != null || keepTtl) {
                        return error(CommandSupport.SYNTAX_ERROR);
                    }
                    keepTtl = true;
                }
                case "EX", "PX", "EXAT", "PXAT" -> {
                    if (expiresAt != null || keepTtl || i + 1 >= arguments.size()) {
                        return error(CommandSupport.SYNTAX_ERROR);
                    }
                    long amount;
                    try {
                        amount = Long.parseLong(arguments.get(++i));
                    } catch (NumberFormatException e) {
                        return error(CommandSupport.NOT_AN_INTEGER);
                    }
                    if (amount <= 0) {
                        return error(CommandSupport.invalidExpire("set"));
                    }
                    try {
                        expiresAt = switch (option) {
                            case "EX" -> Math.addExact(now, Math.multiplyExact(amount, 1000L));
                            case "PX" -> Math.addExact(now, amount);
                            case "EXAT" -> Math.multiplyExact(amount, 1000L);
                            default -> amount;
                        };
                    } catch (ArithmeticException e) {
                        return error(CommandSupport.invalidExpire("set"));
                    }
                }
                default -> {
                    return error(CommandSupport.SYNTAX_ERROR);
                }
            }
        }
        return new Parsed(new SetOptions(expiresAt, keepTtl, condition, get), null);
    }

    private static Parsed error(ErrorValue error) {
        return new Parsed(null, error);
    }
}
