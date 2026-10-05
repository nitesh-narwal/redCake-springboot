package me.niteshh.redcake.command.commands.reads;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.store.*;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code HELLO [2]} - protocol handshake. RedCake speaks RESP2 only, so
 * {@code HELLO 3} is refused with {@code NOPROTO}; clients then fall back to
 * RESP2 automatically.
 */
@Component
public class HelloCommand implements RedCakeCommand {
    @Override
    public String name() {
        return "HELLO";
    }

    @Override
    public RespValue execute(List<String> arguments) {
        if (!arguments.isEmpty() && !"2".equals(arguments.getFirst())) {
            return new ErrorValue("NOPROTO sorry, this protocol version is not supported");
        }
        return new ArrayValue(List.of(
                new BulkString("server"), new BulkString("redcake"),
                new BulkString("version"), new BulkString(InfoCommand.VERSION),
                new BulkString("proto"), new IntegerValue(2),
                new BulkString("mode"), new BulkString("standalone")
        ));
    }
}
