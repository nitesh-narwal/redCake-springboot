package me.niteshh.redcake.command.commands.reads;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.store.*;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@code SELECT index} - only database 0 exists; accepted so client libraries can connect. */
@Component
public class SelectCommand implements RedCakeCommand {
    @Override
    public String name() {
        return "SELECT";
    }

    @Override
    public RespValue execute(List<String> arguments) {
        if (arguments.size() != 1) {
            return CommandSupport.wrongArity("select");
        }
        return "0".equals(arguments.getFirst())
                ? new SimpleString("OK")
                : new ErrorValue("DB index is out of range");
    }
}
