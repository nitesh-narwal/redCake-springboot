package me.niteshh.redcake.command.commands.write;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.store.*;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@code APPEND key value} - append to the value (creating it); replies the new length in bytes. */
@Component
@RequiredArgsConstructor
public class AppendCommand implements RedCakeCommand {
    private final KeyValueStore store;

    @Override
    public String name() {
        return "APPEND";
    }

    @Override
    public boolean isWrite() {
        return true;
    }

    @Override
    public RespValue execute(List<String> arguments) {
        if (arguments.size() != 2) {
            return CommandSupport.wrongArity("append");
        }
        return new IntegerValue(store.append(arguments.get(0), arguments.get(1)));
    }
}
