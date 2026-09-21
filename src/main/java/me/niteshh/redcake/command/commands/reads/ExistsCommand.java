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
public class ExistsCommand implements RedCakeCommand {
    private final KeyValueStore store;

    @Override
    public String name() {
        return "EXISTS";
    }

    @Override
    public RespValue execute(List<String> arguments) {

        if (arguments.isEmpty()) {
            return new ErrorValue("wrong number of arguments for 'exists' command");
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
