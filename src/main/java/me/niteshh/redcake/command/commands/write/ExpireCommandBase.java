package me.niteshh.redcake.command.commands.write;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.store.*;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Shared implementation of EXPIRE / PEXPIRE / EXPIREAT / PEXPIREAT
 * ({@code cmd key time [NX|XX|GT|LT]}).
 *
 * <p>All four are executed internally as {@code PEXPIREAT}: {@link #normalize}
 * converts seconds/relative forms into an absolute millisecond deadline using
 * one clock reading, which is what gets replicated and logged. A deadline in
 * the past deletes the key (so {@code EXPIRE k 0} deletes, as in Redis).
 */
public abstract class ExpireCommandBase implements RedCakeCommand {

    private final KeyValueStore store;
    private final String commandName;
    private final long unitMillis;
    private final boolean relative;

    protected ExpireCommandBase(
            KeyValueStore store,
            String commandName,
            long unitMillis,
            boolean relative
    ) {
        this.store = store;
        this.commandName = commandName;
        this.unitMillis = unitMillis;
        this.relative = relative;
    }

    @Override
    public String name() {
        return commandName;
    }

    @Override
    public boolean isWrite() {
        return true;
    }

    @Override
    public boolean growsMemory() {
        return false;
    }

    @Override
    public RespValue execute(List<String> arguments) {
        if (arguments.size() < 2 || arguments.size() > 3) {
            return CommandSupport.wrongArity(commandName);
        }

        ExpireCondition condition = ExpireCondition.NONE;
        if (arguments.size() == 3) {
            try {
                condition = ExpireCondition.valueOf(
                        arguments.get(2).toUpperCase(java.util.Locale.ROOT)
                );
            } catch (IllegalArgumentException e) {
                return new ErrorValue("Unsupported option " + arguments.get(2));
            }
        }

        long amount;
        try {
            amount = Long.parseLong(arguments.get(1));
        } catch (NumberFormatException e) {
            return CommandSupport.NOT_AN_INTEGER;
        }

        long deadline;
        try {
            deadline = deadline(amount, System.currentTimeMillis());
        } catch (ArithmeticException e) {
            return CommandSupport.invalidExpire(commandName);
        }

        return new IntegerValue(store.expire(arguments.get(0), deadline, condition) ? 1 : 0);
    }

    @Override
    public List<String> normalize(List<String> command, long nowMillis) {
        if (command.size() < 3 || command.size() > 4) {
            return command;
        }
        try {
            long deadline = deadline(Long.parseLong(command.get(2)), nowMillis);
            List<String> rewritten = new java.util.ArrayList<>(command.size());
            rewritten.add("PEXPIREAT");
            rewritten.add(command.get(1));
            rewritten.add(Long.toString(deadline));
            if (command.size() == 4) {
                rewritten.add(command.get(3));
            }
            return rewritten;
        } catch (NumberFormatException | ArithmeticException e) {
            return command; // execute() reports the proper error
        }
    }

    /** Converts the user-supplied amount to an absolute epoch-millisecond deadline. */
    private long deadline(long amount, long now) {
        long millis = Math.multiplyExact(amount, unitMillis);
        return relative ? Math.addExact(now, millis) : millis;
    }
}
