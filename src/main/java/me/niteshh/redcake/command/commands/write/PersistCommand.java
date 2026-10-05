package me.niteshh.redcake.command.commands.write;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.store.*;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@code PERSIST key} - remove the TTL; replies 1 if a TTL was removed. */
@Component
@RequiredArgsConstructor
public class PersistCommand implements RedCakeCommand {
    private final KeyValueStore store;

    @Override
    public String name() {
        return "PERSIST";
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
            return CommandSupport.wrongArity("persist");
        }
        return new IntegerValue(store.persist(arguments.getFirst()) ? 1 : 0);
    }
}
