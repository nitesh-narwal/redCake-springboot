package me.niteshh.redcake.command.commands.reads;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.store.*;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@code PING [message]} - liveness check; replies PONG or echoes the message. */
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
        return CommandSupport.wrongArity("ping");
    }
}
