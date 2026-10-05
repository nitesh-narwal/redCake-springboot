package me.niteshh.redcake.command;

import me.niteshh.redcake.resp.ErrorValue;

/**
 * Lets deeply nested command code abort with a ready-made error reply
 * ({@code throw new CommandException(...)}) instead of threading
 * {@code ErrorValue} return values through every helper.
 * {@link CommandHandler#handle} converts it into the reply. It carries no
 * stack trace because it is ordinary control flow, not a bug.
 */
public class CommandException extends RuntimeException {

    private final ErrorValue error;

    public CommandException(ErrorValue error) {
        super(error.message(), null, false, false);
        this.error = error;
    }

    public CommandException(String message) {
        this(new ErrorValue(message));
    }

    /** @return the reply to send to the client */
    public ErrorValue error() {
        return error;
    }
}
