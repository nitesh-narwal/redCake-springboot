package me.niteshh.redcake.command.commands.reads;

import me.niteshh.redcake.command.CommandHandler;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.ArrayValue;
import me.niteshh.redcake.resp.IntegerValue;
import me.niteshh.redcake.resp.RespValue;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * {@code COMMAND [COUNT | LIST | DOCS | INFO ...]}. Client libraries call it
 * on connect to discover commands.
 *
 * <ul>
 *   <li>{@code COMMAND COUNT} - number of registered commands.</li>
 *   <li>{@code COMMAND LIST} - their names.</li>
 *   <li>anything else ({@code COMMAND}, {@code DOCS}, {@code INFO}) - an empty
 *       array, which every client treats as "no metadata available".</li>
 * </ul>
 *
 * The {@link CommandHandler} is resolved lazily because the handler itself
 * contains this command (a plain constructor dependency would be circular).
 */
@Component
public class CommandInfoCommand implements RedCakeCommand {

    private final ObjectProvider<CommandHandler> handler;

    public CommandInfoCommand(ObjectProvider<CommandHandler> handler) {
        this.handler = handler;
    }

    @Override
    public String name() {
        return "COMMAND";
    }

    @Override
    public RespValue execute(List<String> arguments) {
        if (arguments.isEmpty()) {
            return ArrayValue.empty();
        }
        return switch (arguments.getFirst().toUpperCase(Locale.ROOT)) {
            case "COUNT" -> new IntegerValue(handler.getObject().commandCount());
            case "LIST" -> ArrayValue.ofBulkStrings(handler.getObject().commandNames());
            case "DOCS", "INFO" -> ArrayValue.empty();
            default -> CommandSupport.SYNTAX_ERROR;
        };
    }
}
