package me.niteshh.redcake.command.commands.reads;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.stats.SlowLog;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code SLOWLOG GET [count] | LEN | RESET} - the slowest recent commands.
 * Each entry: {@code [id, unix-time, micros, [args...], client-addr, client-name]}.
 * Administrative because arguments may contain data.
 */
@Component
@RequiredArgsConstructor
public class SlowlogCommand implements RedCakeCommand {
    private final SlowLog slowLog;

    @Override
    public String name() {
        return "SLOWLOG";
    }

    @Override
    public boolean isAdmin() {
        return true;
    }

    @Override
    public RespValue execute(List<String> arguments) {
        if (arguments.isEmpty()) {
            return CommandSupport.wrongArity("slowlog");
        }
        return switch (arguments.getFirst().toUpperCase(Locale.ROOT)) {
            case "LEN" -> new IntegerValue(slowLog.length());
            case "RESET" -> {
                slowLog.reset();
                yield new SimpleString("OK");
            }
            case "GET" -> {
                int count = arguments.size() > 1 ? (int) Math.min(CommandSupport.parseLong(arguments.get(1)), 1_000) : 10;
                List<RespValue> out = new ArrayList<>();
                for (SlowLog.Entry e : slowLog.get(count)) {
                    out.add(new ArrayValue(List.of(
                            new IntegerValue(e.id()),
                            new IntegerValue(e.timestampSeconds()),
                            new IntegerValue(e.micros()),
                            ArrayValue.ofBulkStrings(e.arguments()),
                            new BulkString(e.clientAddress()),
                            new BulkString(e.clientName()))));
                }
                yield new ArrayValue(out);
            }
            default -> new ErrorValue("unknown subcommand '" + arguments.getFirst() + "' for 'slowlog' command");
        };
    }
}
