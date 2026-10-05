package me.niteshh.redcake.command.commands.reads;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.store.*;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@code STRLEN key} - length of the value in bytes (0 if missing). */
@Component
@RequiredArgsConstructor
public class StrlenCommand implements RedCakeCommand {
    private final KeyValueStore store;

    @Override
    public String name() {
        return "STRLEN";
    }

    @Override
    public RespValue execute(List<String> arguments) {
        if (arguments.size() != 1) {
            return CommandSupport.wrongArity("strlen");
        }
        String value = store.get(arguments.getFirst());
        // Values are byte strings, so the char count is the byte count.
        return new IntegerValue(value == null ? 0 : value.length());
    }
}
