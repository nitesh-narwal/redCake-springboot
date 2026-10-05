package me.niteshh.redcake.command.commands.write;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.store.*;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@code DEL key [key ...]} - delete keys; replies how many existed. */
@Component
@RequiredArgsConstructor
public class DelCommand implements RedCakeCommand {
    private final KeyValueStore store;

    @Override
    public String name() {
        return "DEL";
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
        if (arguments.isEmpty()) {
            return CommandSupport.wrongArity("del");
        }
        long deleted = 0;
        for (String key : arguments) {
            if (store.delete(key)) {
                deleted++;
            }
        }
        return new IntegerValue(deleted);
    }
}
