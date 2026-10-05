package me.niteshh.redcake.command;

import me.niteshh.redcake.resp.ErrorValue;

/**
 * Small helpers shared by command implementations so error texts and
 * argument parsing stay identical across commands (client libraries match on
 * the texts).
 */
public final class CommandSupport {

    /** Reply for non-numeric or out-of-range integer arguments. */
    public static final ErrorValue NOT_AN_INTEGER =
            new ErrorValue("value is not an integer or out of range");

    /** Reply for non-numeric floating point arguments. */
    public static final ErrorValue NOT_A_FLOAT =
            new ErrorValue("value is not a valid float");

    /** Reply for unrecognised option combinations. */
    public static final ErrorValue SYNTAX_ERROR = new ErrorValue("syntax error");

    private CommandSupport() {
    }

    /** @return the standard "wrong number of arguments" error for {@code commandName} */
    public static ErrorValue wrongArity(String commandName) {
        return new ErrorValue(
                "wrong number of arguments for '" + commandName.toLowerCase() + "' command"
        );
    }

    /** @return the standard "invalid expire time" error for {@code commandName} */
    public static ErrorValue invalidExpire(String commandName) {
        return new ErrorValue(
                "invalid expire time in '" + commandName.toLowerCase() + "' command"
        );
    }

    /**
     * Parses a 64-bit integer argument.
     *
     * @throws CommandException with {@link #NOT_AN_INTEGER} if it is not one
     */
    public static long parseLong(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new CommandException(NOT_AN_INTEGER);
        }
    }

    /**
     * Parses a floating point argument (NaN rejected, {@code inf}/{@code -inf} accepted).
     *
     * @throws CommandException with {@link #NOT_A_FLOAT} if it is invalid
     */
    public static double parseDouble(String text) {
        try {
            return me.niteshh.redcake.store.ZSet.parseScore(text);
        } catch (NumberFormatException e) {
            throw new CommandException(NOT_A_FLOAT);
        }
    }

    /**
     * Resolves Redis-style inclusive range indexes (negative = from the end)
     * against a collection of {@code size} elements.
     *
     * @return {@code {from, to}} inclusive, or {@code null} if the range is empty
     */
    public static int[] range(long start, long stop, int size) {
        long from = start < 0 ? size + start : start;
        long to = stop < 0 ? size + stop : stop;
        from = Math.max(from, 0);
        to = Math.min(to, size - 1L);
        if (from > to || from >= size) {
            return null;
        }
        return new int[]{(int) from, (int) to};
    }
}
