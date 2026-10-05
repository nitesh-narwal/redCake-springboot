package me.niteshh.redcake.command.commands.reads;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.store.*;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@code DBSIZE} - number of keys (O(1); may briefly include expired keys not yet reaped). */
@Component
@RequiredArgsConstructor
public class DbSizeCommand implements RedCakeCommand {
    private final KeyValueStore store;

    @Override
    public String name() {
        return "DBSIZE";
    }

    @Override
    public RespValue execute(List<String> arguments) {
        if (!arguments.isEmpty()) {
            return CommandSupport.wrongArity("dbsize");
        }
        return new IntegerValue(store.size());
    }
}
