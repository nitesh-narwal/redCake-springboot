package me.niteshh.redcake.command.commands.write;

import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.*;
import me.niteshh.redcake.store.*;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code SETEX key seconds value} and {@code PSETEX key millis value}:
 * set with a relative TTL. Normalization turns them into
 * {@code SET key value PXAT <abs>} so replicas see an absolute deadline.
 */
public abstract class SetExpireCommandBase implements RedCakeCommand {

    private final KeyValueStore store;
    private final String commandName;
    private final long unitMillis;

    protected SetExpireCommandBase(KeyValueStore store, String commandName, long unitMillis) {
        this.store = store;
        this.commandName = commandName;
        this.unitMillis = unitMillis;
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
    public RespValue execute(List<String> arguments) {
        if (arguments.size() != 3) {
            return CommandSupport.wrongArity(commandName);
        }
        Long deadline;
        try {
            deadline = deadline(arguments.get(1), System.currentTimeMillis());
        } catch (NumberFormatException e) {
            return CommandSupport.NOT_AN_INTEGER;
        }
        if (deadline == null) {
            return CommandSupport.invalidExpire(commandName);
        }
        store.set(arguments.get(0), arguments.get(2), deadline);
        return new SimpleString("OK");
    }

    @Override
    public List<String> normalize(List<String> command, long nowMillis) {
        if (command.size() != 4) {
            return command;
        }
        try {
            Long deadline = deadline(command.get(2), nowMillis);
            if (deadline == null) {
                return command;
            }
            return List.of("SET", command.get(1), command.get(3), "PXAT", Long.toString(deadline));
        } catch (NumberFormatException e) {
            return command;
        }
    }

    /** @return absolute deadline, or {@code null} if non-positive/overflowing */
    private Long deadline(String amountText, long now) {
        long amount = Long.parseLong(amountText);
        if (amount <= 0) {
            return null;
        }
        try {
            return Math.addExact(now, Math.multiplyExact(amount, unitMillis));
        } catch (ArithmeticException e) {
            return null;
        }
    }
}
