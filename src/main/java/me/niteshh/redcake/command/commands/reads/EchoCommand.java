package me.niteshh.redcake.command.commands.reads;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.store.*;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@code ECHO message} - returns the message unchanged (read-only; was misplaced in the write package). */
@Component
public class EchoCommand implements RedCakeCommand {
    @Override
    public String name() {
        return "ECHO";
    }

    @Override
    public RespValue execute(List<String> arguments) {
        if (arguments.size() != 1) {
            return CommandSupport.wrongArity("echo");
        }
        return new BulkString(arguments.get(0));
    }
}
