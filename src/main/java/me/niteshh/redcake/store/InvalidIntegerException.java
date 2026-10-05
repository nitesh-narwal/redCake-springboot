package me.niteshh.redcake.store;

/**
 * Thrown by {@link KeyValueStore#increment} when the stored value is not a
 * 64-bit integer or the arithmetic would overflow. Commands translate it into
 * an error reply; the stored value is left untouched.
 */
public class InvalidIntegerException extends RuntimeException {
    public InvalidIntegerException(String message) {
        super(message);
    }
}
