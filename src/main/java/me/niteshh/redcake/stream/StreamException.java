package me.niteshh.redcake.stream;

/**
 * A stream command that cannot be carried out; the message is the error reply. Thrown <em>before</em> anything is
 * changed (the operations check first, then apply), and without a stack trace: it is an ordinary answer, not a bug.
 */
public final class StreamException extends RuntimeException {

    public StreamException(String message) {
        super(message, null, false, false);
    }
}
