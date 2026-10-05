package me.niteshh.redcake.command;

import me.niteshh.redcake.resp.RespValue;

import java.util.List;
import java.util.function.Function;

/**
 * A {@link RedCakeCommand} defined by a name, an arity range and a function.
 * It removes the boilerplate of a one-class-per-command layout for families
 * of small related commands while still plugging into the same registry.
 *
 * <p>Arity is checked before the body runs, so bodies can index their
 * arguments freely. Bodies may throw {@link CommandException} to return an error.
 */
public final class LambdaCommand implements RedCakeCommand {

    private final String name;
    private final int minArgs;
    private final int maxArgs;
    private final boolean write;
    private final boolean growsMemory;
    private final boolean admin;
    private final Function<List<String>, RespValue> body;

    private LambdaCommand(
            String name, int minArgs, int maxArgs,
            boolean write, boolean growsMemory, boolean admin,
            Function<List<String>, RespValue> body
    ) {
        this.name = name;
        this.minArgs = minArgs;
        this.maxArgs = maxArgs;
        this.write = write;
        this.growsMemory = growsMemory;
        this.admin = admin;
        this.body = body;
    }

    /** A read-only command taking between {@code min} and {@code max} arguments ({@code max < 0}: unlimited). */
    public static LambdaCommand read(String name, int min, int max, Function<List<String>, RespValue> body) {
        return new LambdaCommand(name, min, max, false, false, false, body);
    }

    /** A write command that can grow memory (rejected with OOM when the limit is hit). */
    public static LambdaCommand write(String name, int min, int max, Function<List<String>, RespValue> body) {
        return new LambdaCommand(name, min, max, true, true, false, body);
    }

    /** A write command that only removes data (still allowed when memory is full). */
    public static LambdaCommand shrink(String name, int min, int max, Function<List<String>, RespValue> body) {
        return new LambdaCommand(name, min, max, true, false, false, body);
    }

    /** A read-only administrative command (needs the admin role). */
    public static LambdaCommand admin(String name, int min, int max, Function<List<String>, RespValue> body) {
        return new LambdaCommand(name, min, max, false, false, true, body);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean isWrite() {
        return write;
    }

    @Override
    public boolean growsMemory() {
        return growsMemory;
    }

    @Override
    public boolean isAdmin() {
        return admin;
    }

    @Override
    public RespValue execute(List<String> arguments) {
        int count = arguments.size();
        if (count < minArgs || (maxArgs >= 0 && count > maxArgs)) {
            return CommandSupport.wrongArity(name);
        }
        return body.apply(arguments);
    }
}
