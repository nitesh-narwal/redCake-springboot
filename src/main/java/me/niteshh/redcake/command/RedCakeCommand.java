package me.niteshh.redcake.command;

import me.niteshh.redcake.resp.RespValue;

import java.util.List;

/**
 * One server command (GET, SET, ...). Each implementation is a Spring
 * {@code @Component}; {@link CommandHandler} collects them all and dispatches
 * by {@link #name()}, so adding a command means adding one class - no
 * registry to edit.
 */
public interface RedCakeCommand {

    /** @return upper-case command name as sent by clients, e.g. {@code "GET"} */
    String name();

    /**
     * Executes the command.
     *
     * @param arguments the words after the command name
     * @return the reply; arity/type problems are returned as {@code ErrorValue}, not thrown
     */
    RespValue execute(List<String> arguments);

    /**
     * @return {@code true} if the command can change data. Write commands are
     * rejected on replicas, serialized by the primary write lock, appended to
     * the AOF and replicated. Declared here (not in a central list) so a new
     * write command cannot silently miss replication.
     */
    default boolean isWrite() {
        return false;
    }

    /**
     * @return {@code true} if running the command can increase memory use.
     * When {@code maxmemory} is exhausted and nothing can be evicted, only
     * these commands are refused with {@code -OOM}; deletes still work so the
     * operator can free space. Defaults to {@link #isWrite()}.
     */
    default boolean growsMemory() {
        return isWrite();
    }

    /**
     * @return {@code true} for administrative commands (FLUSHALL, CONFIG, ...)
     * that only users with the {@code admin} role may run.
     */
    default boolean isAdmin() {
        return false;
    }

    /**
     * Optional read-only work for a write command, done <em>before</em> the primary write lock is taken, on the
     * client's own thread - so on a multi-core machine the connections do it in parallel and the lock is held only
     * for what really has to be serialized. What it returns is handed to {@link #execute} (same thread, same
     * command) through {@link PreparedWork}. The world may change before the lock is acquired, so {@code execute}
     * must treat it as a hint and re-check; a command that does not override this loses nothing.
     *
     * <p>Must not modify any data, and must never throw (return {@code null} instead).
     *
     * @param command full command including its name, as received from the client
     * @return the prepared work, or {@code null} if there is none
     */
    default Object prepare(List<String> command) {
        return null;
    }

    /**
     * Asks whether this command line wants to <em>wait</em> for data when there is none yet (a stream read with
     * {@code BLOCK}). Runs on the client's own thread, before the command is executed, and must not change data.
     *
     * @param command full command including its name, as received from the client
     * @return what to wait for, or {@code null} if the command does not block (the default, and what a command
     *         returns for a line that is malformed - {@link #execute} reports the error)
     */
    default BlockingRequest blocking(List<String> command) {
        return null;
    }

    /**
     * Rewrites the command into a deterministic form <em>before</em> it is
     * executed, logged and replicated. Relative times ({@code EX 10},
     * {@code EXPIRE k 10}) become absolute ({@code PXAT <ms>}) using one single
     * clock reading, so primary, replicas and AOF replay all agree on the exact
     * deadline instead of each adding "10 seconds" to their own "now".
     *
     * <p>Must never throw: if the arguments are malformed return the command
     * unchanged and let {@link #execute} produce the error reply.
     *
     * @param command  full command including its name
     * @param nowMillis the clock reading to use for relative-to-absolute conversion
     */
    default List<String> normalize(List<String> command, long nowMillis) {
        return command;
    }
}
