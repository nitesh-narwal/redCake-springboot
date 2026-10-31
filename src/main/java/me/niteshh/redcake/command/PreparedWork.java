package me.niteshh.redcake.command;

/**
 * Hands the result of {@link RedCakeCommand#prepare} from the code that took it (before the write lock) to the
 * command's {@code execute} (inside the lock). Both run on the same thread, one command at a time, and the server
 * clears the slot when the command is done, so nothing leaks to the next command of the connection.
 */
public final class PreparedWork {

    private static final ThreadLocal<Object> CURRENT = new ThreadLocal<>();

    private PreparedWork() {
    }

    /** Makes {@code work} (possibly {@code null}) available to the command about to be executed. */
    public static void set(Object work) {
        if (work == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(work);
        }
    }

    /** @return the prepared work of the command being executed if it is a {@code type}, else {@code null}; clears the slot */
    public static <T> T take(Class<T> type) {
        Object work = CURRENT.get();
        CURRENT.remove();
        return type.isInstance(work) ? type.cast(work) : null;
    }

    /** Empties the slot. */
    public static void clear() {
        CURRENT.remove();
    }
}
