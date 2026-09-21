package me.niteshh.redcake.command.commands.reads;

import lombok.AllArgsConstructor;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.ErrorValue;
import me.niteshh.redcake.resp.IntegerValue;
import me.niteshh.redcake.resp.RespValue;
import me.niteshh.redcake.store.KeyValueStore;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@AllArgsConstructor
public class TtlCommand implements RedCakeCommand {

    private final KeyValueStore store;

    @Override
    public String name() {
        return "TTL";
    }

    @Override
    public RespValue execute(List<String> arguments) {

        if (arguments.size() != 1) {
            return new ErrorValue(
                    "wrong number of arguments for 'ttl' command"
            );
        }

        String key = arguments.get(0);

        return new IntegerValue(
                store.ttl(key)
        );
    }
}
