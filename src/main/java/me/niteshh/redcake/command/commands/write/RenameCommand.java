package me.niteshh.redcake.command.commands.write;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.store.*;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@code RENAME source destination} - move a value (and its TTL), overwriting the destination. */
@Component
@RequiredArgsConstructor
public class RenameCommand implements RedCakeCommand {
    private final KeyValueStore store;

    @Override
    public String name() {
        return "RENAME";
    }

    @Override
    public boolean isWrite() {
        return true;
    }

    @Override
    public RespValue execute(List<String> arguments) {
        if (arguments.size() != 2) {
            return CommandSupport.wrongArity("rename");
        }
        return store.rename(arguments.get(0), arguments.get(1))
                ? new SimpleString("OK")
                : new ErrorValue("no such key");
    }
}
