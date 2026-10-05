package me.niteshh.redcake.command.commands.write;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.store.*;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@code SETNX key value} - set only if absent; replies 1 (set) or 0 (already existed). */
@Component
@RequiredArgsConstructor
public class SetNxCommand implements RedCakeCommand {
    private final KeyValueStore store;

    @Override
    public String name() {
        return "SETNX";
    }

    @Override
    public boolean isWrite() {
        return true;
    }

    @Override
    public RespValue execute(List<String> arguments) {
        if (arguments.size() != 2) {
            return CommandSupport.wrongArity("setnx");
        }
        SetResult result = store.set(
                arguments.get(0),
                arguments.get(1),
                new SetOptions(null, false, SetOptions.Condition.ONLY_IF_ABSENT, false)
        );
        return new IntegerValue(result.applied() ? 1 : 0);
    }
}
