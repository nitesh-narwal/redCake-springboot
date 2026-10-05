package me.niteshh.redcake.store;

/**
 * A command was applied to a key holding a different type (for example
 * {@code GET} on a hash). {@code CommandHandler} turns it into the standard
 * {@code -WRONGTYPE ...} error reply; the key is left unchanged.
 */
public class WrongTypeException extends RuntimeException {

    public WrongTypeException() {
        super("WRONGTYPE Operation against a key holding the wrong kind of value");
    }
}
