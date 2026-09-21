package me.niteshh.redcake.command.commands.write;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.ErrorValue;
import me.niteshh.redcake.resp.IntegerValue;
import me.niteshh.redcake.resp.RespValue;
import me.niteshh.redcake.store.InvalidIntegerException;
import me.niteshh.redcake.store.KeyValueStore;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class IncrCommand implements RedCakeCommand {

    private final KeyValueStore store;

    @Override
    public String name() {
        return "INCR";
    }

    @Override
    public RespValue execute(List<String> arguments) {

        if (arguments.size() != 1) {
            return new ErrorValue("wrong number of arguments for 'incr' command");
        }

        String key = arguments.getFirst();

        try {
            long value = store.increment(key, 1);
            return new IntegerValue(value);
        } catch (InvalidIntegerException e) {
            return new ErrorValue(e.getMessage());
        }
    }
}
