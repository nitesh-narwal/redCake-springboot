package me.niteshh.redcake.command.commands.write;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.store.*;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@code DECR key} - subtract 1 (missing key starts at 0); errors if the value is not an integer or would overflow. */
@Component
@RequiredArgsConstructor
public class DecrCommand implements RedCakeCommand {
    private final KeyValueStore store;

    @Override
    public String name() {
        return "DECR";
    }

    @Override
    public boolean isWrite() {
        return true;
    }

    @Override
    public RespValue execute(List<String> arguments) {
        if (arguments.size() != 1) {
            return CommandSupport.wrongArity("decr");
        }
        try {
            return new IntegerValue(store.increment(arguments.getFirst(), -1));
        } catch (InvalidIntegerException e) {
            return new ErrorValue(e.getMessage());
        }
    }
}
