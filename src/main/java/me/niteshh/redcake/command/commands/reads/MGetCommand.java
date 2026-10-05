package me.niteshh.redcake.command.commands.reads;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.store.*;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@code MGET key [key ...]} - values of all keys; missing keys yield null entries. */
@Component
@RequiredArgsConstructor
public class MGetCommand implements RedCakeCommand {
    private final KeyValueStore store;

    @Override
    public String name() {
        return "MGET";
    }

    @Override
    public RespValue execute(List<String> arguments) {
        if (arguments.isEmpty()) {
            return CommandSupport.wrongArity("mget");
        }
        List<RespValue> values = new java.util.ArrayList<>(arguments.size());
        for (String key : arguments) {
            String value;
            try {
                value = store.get(key);
            } catch (WrongTypeException e) {
                value = null; // Redis: MGET reports non-string keys as nil instead of failing
            }
            values.add(value == null ? new NullValue() : new BulkString(value));
        }
        return new ArrayValue(values);
    }
}
