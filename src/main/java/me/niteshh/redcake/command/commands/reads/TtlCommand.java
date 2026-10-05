package me.niteshh.redcake.command.commands.reads;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.store.*;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@code TTL key} - remaining seconds; -2 if the key is missing, -1 if it has no expiry. */
@Component
@RequiredArgsConstructor
public class TtlCommand implements RedCakeCommand {
    private final KeyValueStore store;

    @Override
    public String name() {
        return "TTL";
    }

    @Override
    public RespValue execute(List<String> arguments) {
        if (arguments.size() != 1) {
            return CommandSupport.wrongArity("ttl");
        }
        return new IntegerValue(store.ttl(arguments.getFirst()));
    }
}
