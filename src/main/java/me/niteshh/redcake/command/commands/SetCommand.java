package me.niteshh.redcake.command.commands;

import lombok.AllArgsConstructor;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.ErrorValue;
import me.niteshh.redcake.resp.RespValue;
import me.niteshh.redcake.resp.SimpleString;
import me.niteshh.redcake.store.KeyValueStore;
import org.springframework.stereotype.Component;

import java.util.List;

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
        if (arguments.size() != 2) {
            return new ErrorValue("wrong number of arguments for 'set' command");
        }

        String key = arguments.get(0);
        String value = arguments.get(1);

        store.set(key, value);

        return new SimpleString("OK");
    }
}
