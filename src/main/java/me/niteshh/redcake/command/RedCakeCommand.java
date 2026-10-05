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
