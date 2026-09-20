package me.niteshh.redcake.command.commands;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.ErrorValue;
import me.niteshh.redcake.resp.IntegerValue;
import me.niteshh.redcake.resp.RespValue;
import me.niteshh.redcake.store.KeyValueStore;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PttlCommand implements RedCakeCommand { // This command returns the remaining time to live of a key in milliseconds.

    private final KeyValueStore store;

    public PttlCommand(KeyValueStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "PTTL";
    }

    @Override
    public RespValue execute(List<String> arguments) {

        if (arguments.size() != 1) {
            return new ErrorValue("wrong number of arguments for 'pttl' command");
        }

        String key = arguments.getFirst();

        return new IntegerValue(store.pttl(key));
    }
}
