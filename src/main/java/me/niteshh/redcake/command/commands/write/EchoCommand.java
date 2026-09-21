package me.niteshh.redcake.command.commands.write;

import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.BulkString;
import me.niteshh.redcake.resp.ErrorValue;
import me.niteshh.redcake.resp.RespValue;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class EchoCommand implements RedCakeCommand {

    @Override
    public String name() {
        return "ECHO";
    }

    @Override
    public RespValue execute(List<String> arguments) {
        if (arguments.size() != 1) {
            return new ErrorValue("wrong number of arguments for 'echo' command");
        }

        return new BulkString(arguments.get(0));
    }
}
