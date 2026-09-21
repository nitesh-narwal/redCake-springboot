package me.niteshh.redcake.command.commands.write;

import lombok.AllArgsConstructor;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.ErrorValue;
import me.niteshh.redcake.resp.IntegerValue;
import me.niteshh.redcake.resp.RespValue;
import me.niteshh.redcake.store.InvalidIntegerException;
import me.niteshh.redcake.store.KeyValueStore;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@AllArgsConstructor
public class IncrByCommand implements RedCakeCommand {

    private final KeyValueStore store;

    @Override
    public String name() {
        return "INCRBY";
    }

    @Override
    public RespValue execute(List<String> arguments) {
        if (arguments.size() != 2) {
            return new ErrorValue("wrong number of arguments for 'incrby' command");
        }

        String key = arguments.get(0);
        long amount;

        try {
            amount = Long.parseLong(arguments.get(1));
        } catch (NumberFormatException e) {
            return new ErrorValue("value is not an integer or out of range");
        }

        try {
            long value = store.increment(key, amount);
            return new IntegerValue(value);
        } catch (InvalidIntegerException e) {
            return new ErrorValue(e.getMessage());
        }
    }
}
