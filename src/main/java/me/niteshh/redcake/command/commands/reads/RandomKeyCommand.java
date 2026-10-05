package me.niteshh.redcake.command.commands.reads;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.BulkString;
import me.niteshh.redcake.resp.NullValue;
import me.niteshh.redcake.resp.RespValue;
import me.niteshh.redcake.store.KeyValueStore;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@code RANDOMKEY} - some key of the database, or nil if it is empty (approximately random). */
@Component
@RequiredArgsConstructor
public class RandomKeyCommand implements RedCakeCommand {
    private final KeyValueStore store;

    @Override
    public String name() {
        return "RANDOMKEY";
    }

    @Override
    public RespValue execute(List<String> arguments) {
        if (!arguments.isEmpty()) {
            return CommandSupport.wrongArity("randomkey");
        }
        String key = store.anyKey();
        return key == null ? new NullValue() : new BulkString(key);
    }
}
