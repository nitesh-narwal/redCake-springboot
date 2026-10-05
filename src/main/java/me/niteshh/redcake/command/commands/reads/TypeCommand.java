package me.niteshh.redcake.command.commands.reads;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.store.*;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@code TYPE key} - string, hash, list, set or zset; "none" if the key does not exist. */
@Component
@RequiredArgsConstructor
public class TypeCommand implements RedCakeCommand {
    private final KeyValueStore store;

    @Override
    public String name() {
        return "TYPE";
    }

    @Override
    public RespValue execute(List<String> arguments) {
        if (arguments.size() != 1) {
            return CommandSupport.wrongArity("type");
        }
        ValueType type = store.type(arguments.getFirst());
        return new SimpleString(type == null ? "none" : type.redisName());
    }
}
