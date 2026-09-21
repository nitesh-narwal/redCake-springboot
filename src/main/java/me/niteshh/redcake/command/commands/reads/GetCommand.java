package me.niteshh.redcake.command.commands.reads;

import lombok.AllArgsConstructor;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.BulkString;
import me.niteshh.redcake.resp.ErrorValue;
import me.niteshh.redcake.resp.NullValue;
import me.niteshh.redcake.resp.RespValue;
import me.niteshh.redcake.store.KeyValueStore;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@AllArgsConstructor
public class GetCommand implements RedCakeCommand {
    private final KeyValueStore store;

    @Override
    public String name() {
        return "GET";
    }

    @Override
    public RespValue execute(List<String> arguments) {

        if (arguments.size() != 1) {
            return new ErrorValue("wrong number of arguments for 'get' command");
        }

        String key = arguments.getFirst(); // Get the first argument as the key to retrieve equivalent to arguments.get(0) but more readable
        String value = store.get(key);

        if (value == null) {
            return new NullValue();
        }
        return new BulkString(value);
    }
}
