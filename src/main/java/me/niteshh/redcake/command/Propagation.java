package me.niteshh.redcake.command;

import java.util.List;

/**
 * Lets a write command say <em>what to log and replicate instead of itself</em>, after it knows what it did.
 *
 * <p>Most commands are deterministic once their relative times are made absolute ({@link RedCakeCommand#normalize}),
 * so the normalized command is what replicas and the AOF receive. Some are not: {@code XREADGROUP ... >} hands out
 * <em>whatever entries are new right now</em>, at a time of its own, and {@code XAUTOCLAIM} claims <em>whatever has been
 * idle long enough</em>. Replaying those commands on a replica, or after a restart, would give different answers. So
 * they report their result as plain deterministic commands ({@code XCLAIM id TIME t ... FORCE JUSTID},
 * {@code XGROUP SETID}) which replicas and the AOF replay to arrive at the very same state. Redis does the same.
 *
 * <p>The server calls {@link #begin} before and {@link #end} after executing a write command on the primary; outside
 * of that (a replica applying its stream, the AOF being replayed) {@link #instead} does nothing, because those
 * executions must not be logged again.
 */
public final class Propagation {

    private static final class Slot {
        boolean active;
        List<List<String>> commands;
    }

    private static final ThreadLocal<Slot> SLOT = ThreadLocal.withInitial(Slot::new);

    private Propagation() {
    }

    /** Starts watching the command about to be executed on this thread. */
    public static void begin() {
        Slot slot = SLOT.get();
        slot.active = true;
        slot.commands = null;
    }

    /**
     * From inside a command: log and replicate these commands (possibly none) instead of the command itself.
     * Ignored when the server is not watching.
     */
    public static void instead(List<List<String>> commands) {
        Slot slot = SLOT.get();
        if (slot.active) {
            slot.commands = commands;
        }
    }

    /**
     * Stops watching.
     *
     * @return what the command asked to be propagated instead of itself, or {@code null} if it did not ask for anything
     */
    public static List<List<String>> end() {
        Slot slot = SLOT.get();
        List<List<String>> commands = slot.commands;
        slot.active = false;
        slot.commands = null;
        return commands;
    }
}
