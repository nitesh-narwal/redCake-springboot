package me.niteshh.redcake.command.commands.write;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.ErrorValue;
import me.niteshh.redcake.resp.IntegerValue;
import me.niteshh.redcake.resp.RespValue;
import me.niteshh.redcake.store.KeyValueStore;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class DelCommand  implements RedCakeCommand {

    private final KeyValueStore store;

    @Override
    public String name() {
        return "DEL";
    }

    @Override
    public RespValue execute(List<String> arguments) {

        if (arguments.isEmpty()) {
            return new ErrorValue("wrong number of arguments for 'del' command");
        }

        long deletedCount = 0;

        for (String key : arguments) {

            if (store.delete(key)) {
                deletedCount++;
            }
        }
        return new IntegerValue(deletedCount);
    }
}
