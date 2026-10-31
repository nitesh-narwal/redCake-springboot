package me.niteshh.redcake.json;

/**
 * A JSON text or a JSONPath expression that cannot be parsed, or an operation a document cannot carry out
 * (a path that does not exist, an index out of range ...). The commands turn it into an error reply.
 *
 * <p>It carries no stack trace: like {@code CommandException} it is ordinary control flow, not a bug.
 */
public final class JsonException extends RuntimeException {

    public JsonException(String message) {
        super(message, null, false, false);
    }
}
