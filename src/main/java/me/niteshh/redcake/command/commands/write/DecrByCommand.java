package me.niteshh.redcake.command.commands.write;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.store.*;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@code DECRBY key decrement} - atomic add of a 64-bit integer; errors on non-integer value or overflow. */
@Component
@RequiredArgsConstructor
public class DecrByCommand implements RedCakeCommand {
    private final KeyValueStore store;

    @Override
    public String name() {
        return "DECRBY";
    }

    @Override
    public boolean isWrite() {
        return true;
    }

    @Override
    public RespValue execute(List<String> arguments) {
        if (arguments.size() != 2) {
            return CommandSupport.wrongArity("decrby");
        }

        long amount;
        try {
            amount = Long.parseLong(arguments.get(1));
        } catch (NumberFormatException e) {
            return CommandSupport.NOT_AN_INTEGER;
        }

        // -Long.MIN_VALUE does not fit in a long.
        if (amount == Long.MIN_VALUE) {
            return new ErrorValue("increment or decrement would overflow");
        }

        try {
            return new IntegerValue(store.increment(arguments.get(0), -amount));
        } catch (InvalidIntegerException e) {
            return new ErrorValue(e.getMessage());
        }
    }
}
