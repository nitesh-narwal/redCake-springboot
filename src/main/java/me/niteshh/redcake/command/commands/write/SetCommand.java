package me.niteshh.redcake.command.commands.write;

import lombok.AllArgsConstructor;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.ErrorValue;
import me.niteshh.redcake.resp.RespValue;
import me.niteshh.redcake.resp.SimpleString;
import me.niteshh.redcake.store.KeyValueStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
@AllArgsConstructor
public class SetCommand implements RedCakeCommand {
    private final KeyValueStore store;

    @Override
    public String name() {
        return "SET";
    }

    @Override
    public RespValue execute(List<String> arguments) {

        if (arguments.size() < 2) {
            return new ErrorValue("wrong number of arguments for 'set' command");
        }

        String key = arguments.get(0);
        String value = arguments.get(1);

        // Normal SET
        if (arguments.size() == 2) {
            store.set(key, value);
            return new SimpleString("OK");
        }

        // SET key value EX seconds
        if (arguments.size() == 4) {
            String option = arguments.get(2).toUpperCase(Locale.ROOT);
            long duration;

            try {
                duration = Long.parseLong(arguments.get(3));
            } catch (NumberFormatException e) {
                return new ErrorValue("invalid expire time");
            }

            if (duration <= 0) {
                return new ErrorValue("invalid expire time");
            }

            long expiresAt;

            if (option.equals("EX")) {
                expiresAt = System.currentTimeMillis() + duration * 1000;
            } else if (option.equals("PX")) { // PX (Per-millisecond) option for expiration time in milliseconds
                expiresAt = System.currentTimeMillis() + duration;
            } else {
                return new ErrorValue("syntax error");
            }

            store.set(key, value, expiresAt);

            return new SimpleString("OK");
        }
        return new ErrorValue("syntax error");
    }
}
