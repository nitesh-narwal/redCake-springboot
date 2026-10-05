package me.niteshh.redcake.command.commands.write;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.store.*;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@code UNLINK key [key ...]} - same as DEL (memory is reclaimed by the JVM GC anyway). */
@Component
@RequiredArgsConstructor
public class UnlinkCommand implements RedCakeCommand {
    private final KeyValueStore store;

    @Override
    public String name() {
        return "UNLINK";
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
            return CommandSupport.wrongArity("unlink");
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
