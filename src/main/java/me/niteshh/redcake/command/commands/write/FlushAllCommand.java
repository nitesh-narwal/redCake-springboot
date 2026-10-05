package me.niteshh.redcake.command.commands.write;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.store.*;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@code FLUSHALL [ASYNC|SYNC]} - delete every key. */
@Component
@RequiredArgsConstructor
public class FlushAllCommand implements RedCakeCommand {
    private final KeyValueStore store;

    @Override
    public String name() {
        return "FLUSHALL";
    }

    @Override
    public boolean isWrite() {
        return true;
    }

    @Override
    public boolean isAdmin() {
        return true;
    }

    @Override
    public boolean growsMemory() {
        return false;
    }

    @Override
    public RespValue execute(List<String> arguments) {
        if (arguments.size() > 1) {
            return CommandSupport.wrongArity("flushall");
        }
        if (arguments.size() == 1
                && !"ASYNC".equalsIgnoreCase(arguments.getFirst())
                && !"SYNC".equalsIgnoreCase(arguments.getFirst())) {
            return CommandSupport.SYNTAX_ERROR;
        }
        store.clear();
        return new SimpleString("OK");
    }
}
