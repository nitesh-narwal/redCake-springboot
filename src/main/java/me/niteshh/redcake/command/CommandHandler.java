package me.niteshh.redcake.command;

import me.niteshh.redcake.resp.ErrorValue;
import me.niteshh.redcake.resp.RespValue;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class CommandHandler {
    // The CommandHandler class is responsible for processing and executing commands received from clients.
    // It acts as a bridge between the parsed commands and the underlying logic that performs the requested operations.
    // The class may contain methods to handle different types of commands, validate input, and return appropriate responses to the clients.

    /**
     * Handles a list of commands.
     *  -> we got ECHO  @Com_function(List<String> commands){
     *
     *      for(int i=0; i<commands.size(); i++){
     *          b[i] = commands.get(i);
     *          if ECHO {
     *              return rephrase(b[i + 1]);
     *            }
     *     }
     *  }
     * */

    private final Map<String, RedCakeCommand> commands;

    public CommandHandler(List<RedCakeCommand> commandList) {
        this.commands = commandList.stream()
                .collect(Collectors.toMap(
                        command -> command.name().toUpperCase(Locale.ROOT),
                        Function.identity()
                ));
    }

    /**
     * Handles a single command.
     *
     * @param command the command to handle
     * @return the result of the command
     */
    public RespValue handle(List<String> command) {

        if (command == null || command.isEmpty()) {
            return new ErrorValue("empty command");
        }

        String commandName = command.get(0).toUpperCase(Locale.ROOT);
        RedCakeCommand redCakeCommand = commands.get(commandName);

        if (redCakeCommand == null) {
            return new ErrorValue("unknown command '" + commandName + "'");
        }

        List<String> arguments = command.subList(1, command.size());
        return redCakeCommand.execute(arguments);
    }

}
