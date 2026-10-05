package me.niteshh.redcake.command.commands.write;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.store.*;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@code INCR key} - add 1 (missing key starts at 0); errors if the value is not an integer or would overflow. */
@Component
@RequiredArgsConstructor
public class IncrCommand implements RedCakeCommand {
    private final KeyValueStore store;

    @Override
    public String name() {
        return "INCR";
    }

    @Override
    public boolean isWrite() {
        return true;
    }

    @Override
    public RespValue execute(List<String> arguments) {
        if (arguments.size() != 1) {
            return CommandSupport.wrongArity("incr");
        }
        try {
            return new IntegerValue(store.increment(arguments.getFirst(), 1));
        } catch (InvalidIntegerException e) {
            return new ErrorValue(e.getMessage());
        }
    }
}
