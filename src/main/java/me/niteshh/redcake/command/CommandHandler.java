package me.niteshh.redcake.command;

import me.niteshh.redcake.resp.ErrorValue;
import me.niteshh.redcake.resp.RespValue;
import me.niteshh.redcake.store.WrongTypeException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.Collectors;

/**
 * Command router: finds the {@link RedCakeCommand} for a parsed command line
 * and runs it. It knows nothing about sockets, replication or auth - those
 * concerns live in {@code ClientHandler} - which keeps it trivial to unit
 * test and lets the replica apply the replication stream through the very
 * same code path as normal clients.
 */
@Component
public class CommandHandler {

    /** Upper-case command name to implementation. Built once, then read-only. */
    private final Map<String, RedCakeCommand> commands;

    /**
     * @param commandList every {@code RedCakeCommand} bean (injected by Spring)
     * @param providers   beans contributing more commands (data-type families)
     * @throws IllegalStateException if two commands share a name
     */
    @Autowired
    public CommandHandler(List<RedCakeCommand> commandList, List<CommandProvider> providers) {
        this.commands = Stream.concat(
                        commandList.stream(),
                        providers.stream().flatMap(provider -> provider.commands().stream()))
                .collect(Collectors.toMap(
                        command -> command.name().toUpperCase(Locale.ROOT),
                        Function.identity()
                ));
    }

    /** Convenience constructor for tests and simple setups without providers. */
    public CommandHandler(List<RedCakeCommand> commandList) {
        this(commandList, List.of());
    }

    /**
     * Executes one command.
     *
     * @param command command name followed by its arguments
     * @return the reply (an {@code ErrorValue} for unknown commands)
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

        try {
            return redCakeCommand.execute(command.subList(1, command.size()));
        } catch (WrongTypeException e) {
            return new ErrorValue(e.getMessage());
        } catch (CommandException e) {
            return e.error();
        }
    }

    /**
     * Converts relative times to absolute ones (see
     * {@link RedCakeCommand#normalize}). Unknown commands are returned as-is.
     */
    public List<String> normalize(List<String> command, long nowMillis) {
        RedCakeCommand redCakeCommand = lookup(command);
        return redCakeCommand == null
                ? command
                : redCakeCommand.normalize(command, nowMillis);
    }

    /** @return number of registered commands (reported by {@code COMMAND COUNT}) */
    public int commandCount() {
        return commands.size();
    }

    /** @return sorted lower-case names of all registered commands ({@code COMMAND LIST}) */
    public List<String> commandNames() {
        return commands.keySet().stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .sorted()
                .toList();
    }

    /** @return the command registered under {@code name} (any case), or {@code null} */
    public RedCakeCommand find(String name) {
        return commands.get(name.toUpperCase(Locale.ROOT));
    }

    private RedCakeCommand lookup(List<String> command) {
        if (command == null || command.isEmpty()) {
            return null;
        }
        return find(command.getFirst());
    }
}
