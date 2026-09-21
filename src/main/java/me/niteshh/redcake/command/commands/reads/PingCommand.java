package me.niteshh.redcake.command.commands.reads;

import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.BulkString;
import me.niteshh.redcake.resp.ErrorValue;
import me.niteshh.redcake.resp.RespValue;
import me.niteshh.redcake.resp.SimpleString;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PingCommand implements RedCakeCommand {
    @Override
    public String name() {
        return "PING";
    }

    @Override
    public RespValue execute(List<String> arguments) {

        if (arguments.isEmpty()) {
            return new SimpleString("PONG");
        }

        if (arguments.size() == 1) {
            return new BulkString(arguments.get(0));
        }

        return new ErrorValue("wrong number of arguments for 'ping' command");
    }
}
