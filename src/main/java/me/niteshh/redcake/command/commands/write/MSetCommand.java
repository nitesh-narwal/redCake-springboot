package me.niteshh.redcake.command.commands.write;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.store.*;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@code MSET key value [key value ...]} - set many keys (applied in order under the write lock). */
@Component
@RequiredArgsConstructor
public class MSetCommand implements RedCakeCommand {
    private final KeyValueStore store;

    @Override
    public String name() {
        return "MSET";
    }

    @Override
    public boolean isWrite() {
        return true;
    }

    @Override
    public RespValue execute(List<String> arguments) {
        if (arguments.isEmpty() || arguments.size() % 2 != 0) {
            return CommandSupport.wrongArity("mset");
        }
        for (int i = 0; i < arguments.size(); i += 2) {
            store.set(arguments.get(i), arguments.get(i + 1));
        }
        return new SimpleString("OK");
    }
}
