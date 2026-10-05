package me.niteshh.redcake.command.commands.reads;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.store.*;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@code TIME} - server clock as [unix seconds, microseconds]. */
@Component
public class TimeCommand implements RedCakeCommand {
    @Override
    public String name() {
        return "TIME";
    }

    @Override
    public RespValue execute(List<String> arguments) {
        if (!arguments.isEmpty()) {
            return CommandSupport.wrongArity("time");
        }
        java.time.Instant now = java.time.Instant.now();
        return ArrayValue.ofBulkStrings(List.of(
                Long.toString(now.getEpochSecond()),
                Long.toString(now.getNano() / 1000)
        ));
    }
}
