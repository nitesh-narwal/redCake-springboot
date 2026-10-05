package me.niteshh.redcake.command.commands.reads;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.store.*;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@code PTTL key} - like TTL but in milliseconds. */
@Component
@RequiredArgsConstructor
public class PttlCommand implements RedCakeCommand {
    private final KeyValueStore store;

    @Override
    public String name() {
        return "PTTL";
    }

    @Override
    public RespValue execute(List<String> arguments) {
        if (arguments.size() != 1) {
            return CommandSupport.wrongArity("pttl");
        }
        return new IntegerValue(store.pttl(arguments.getFirst()));
    }
}
