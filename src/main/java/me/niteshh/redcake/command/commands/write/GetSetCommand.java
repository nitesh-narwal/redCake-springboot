package me.niteshh.redcake.command.commands.write;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.store.*;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@code GETSET key value} - atomically set and return the old value (null if none). */
@Component
@RequiredArgsConstructor
public class GetSetCommand implements RedCakeCommand {
    private final KeyValueStore store;

    @Override
    public String name() {
        return "GETSET";
    }

    @Override
    public boolean isWrite() {
        return true;
    }

    @Override
    public RespValue execute(List<String> arguments) {
        if (arguments.size() != 2) {
            return CommandSupport.wrongArity("getset");
        }
        SetResult result = store.set(
                arguments.get(0),
                arguments.get(1),
                new SetOptions(null, false, SetOptions.Condition.ALWAYS, true)
        );
        return result.previousValue() == null
                ? new NullValue()
                : new BulkString(result.previousValue());
    }
}
