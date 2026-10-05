package me.niteshh.redcake.command.commands.reads;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.store.*;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@code GET key} - returns the value, or a null bulk string if missing/expired. */
@Component
@RequiredArgsConstructor
public class GetCommand implements RedCakeCommand {
    private final KeyValueStore store;

    @Override
    public String name() {
        return "GET";
    }

    @Override
    public RespValue execute(List<String> arguments) {
        if (arguments.size() != 1) {
            return CommandSupport.wrongArity("get");
        }
        String value = store.get(arguments.getFirst());
        return value == null ? new NullValue() : new BulkString(value);
    }
}
