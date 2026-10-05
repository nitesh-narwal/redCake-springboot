package me.niteshh.redcake.command.commands.reads;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.store.*;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code KEYS pattern} - all keys matching a glob. O(n) over the whole
 * keyspace: fine for debugging, avoid on large production datasets.
 */
@Component
@RequiredArgsConstructor
public class KeysCommand implements RedCakeCommand {
    private final KeyValueStore store;

    @Override
    public String name() {
        return "KEYS";
    }

    @Override
    public RespValue execute(List<String> arguments) {
        if (arguments.size() != 1) {
            return CommandSupport.wrongArity("keys");
        }
        return ArrayValue.ofBulkStrings(store.keys(arguments.getFirst()));
    }
}
