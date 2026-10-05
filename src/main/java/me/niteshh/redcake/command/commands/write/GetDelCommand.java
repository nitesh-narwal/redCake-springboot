package me.niteshh.redcake.command.commands.write;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.store.*;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@code GETDEL key} - atomically return the value and delete the key. */
@Component
@RequiredArgsConstructor
public class GetDelCommand implements RedCakeCommand {
    private final KeyValueStore store;

    @Override
    public String name() {
        return "GETDEL";
    }

    @Override
    public boolean isWrite() {
        return true;
    }

    @Override
    public boolean growsMemory() {
        return false;
    }

    @Override
    public RespValue execute(List<String> arguments) {
        if (arguments.size() != 1) {
            return CommandSupport.wrongArity("getdel");
        }
        String value = store.getAndDelete(arguments.getFirst());
        return value == null ? new NullValue() : new BulkString(value);
    }
}
