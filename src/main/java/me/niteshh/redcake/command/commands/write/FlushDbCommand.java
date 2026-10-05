package me.niteshh.redcake.command.commands.write;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.store.*;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@code FLUSHDB [ASYNC|SYNC]} - identical to FLUSHALL because there is a single database. */
@Component
@RequiredArgsConstructor
public class FlushDbCommand implements RedCakeCommand {
    private final KeyValueStore store;

    @Override
    public String name() {
        return "FLUSHDB";
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
            return CommandSupport.wrongArity("flushdb");
        }
        store.clear();
        return new SimpleString("OK");
    }
}
