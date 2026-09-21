package me.niteshh.redcake.command.commands.write;

import lombok.AllArgsConstructor;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.ErrorValue;
import me.niteshh.redcake.resp.IntegerValue;
import me.niteshh.redcake.resp.RespValue;
import me.niteshh.redcake.store.KeyValueStore;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@AllArgsConstructor
public class ExpireCommand implements RedCakeCommand {
    private final KeyValueStore store;

    @Override
    public String name() {
        return "EXPIRE";
    }

    @Override
    public RespValue execute(List<String> arguments) {

        if (arguments.size() != 2) {
            return new ErrorValue("wrong number of arguments for 'expire' command");
        }

        String key = arguments.get(0);
        long seconds;

        try {
            seconds = Long.parseLong(arguments.get(1));
        } catch (NumberFormatException e) {
            return new ErrorValue("invalid expire time");
        }

        if (seconds <= 0) {
            return new ErrorValue("invalid expire time");
        }

        long expiresAt = System.currentTimeMillis() + seconds * 1000;

        boolean success = store.expire(key, expiresAt);

        return new IntegerValue(success ? 1 : 0);
    }
}
