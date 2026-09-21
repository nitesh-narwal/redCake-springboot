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
public class DecrByCommand implements RedCakeCommand {

    private final KeyValueStore store;

    @Override
    public String name() {
        return "DECRBY";
    }

    @Override
    public RespValue execute(List<String> arguments) {
        if (arguments.size() != 2) {
            return new ErrorValue("wrong number of arguments for 'decrby' command");
        }

        String key = arguments.get(0);
        long amount;

        try {
            amount = Long.parseLong(arguments.get(1));
        } catch (NumberFormatException e) {
            return new ErrorValue("value is not an integer or out of range");
        }

        /*
         * DECRBY key 5
         * is equivalent to:
         * INCRBY key -5
         */
        if (amount == Long.MIN_VALUE) {
            return new ErrorValue("increment or decrement would overflow");
        }

        try {
            long value = store.increment(key, -amount);
            return new IntegerValue(value);
        } catch (InvalidIntegerException e) {
            return new ErrorValue(e.getMessage());
        }
    }
}
