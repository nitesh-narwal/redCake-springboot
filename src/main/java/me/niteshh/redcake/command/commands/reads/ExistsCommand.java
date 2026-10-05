package me.niteshh.redcake.command.commands.reads;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.store.*;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@code EXISTS key [key ...]} - how many of the keys exist (a repeated key counts each time, as in Redis). */
@Component
@RequiredArgsConstructor
public class ExistsCommand implements RedCakeCommand {
    private final KeyValueStore store;

    @Override
    public String name() {
        return "EXISTS";
    }

    @Override
    public RespValue execute(List<String> arguments) {
        if (arguments.isEmpty()) {
            return CommandSupport.wrongArity("exists");
        }
        long count = 0;
        for (String key : arguments) {
            if (store.exists(key)) {
                count++;
            }
        }
        return new IntegerValue(count);
    }
}
