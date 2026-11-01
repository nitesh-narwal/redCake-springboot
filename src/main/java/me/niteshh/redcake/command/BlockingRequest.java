package me.niteshh.redcake.command;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * A command that wants to wait when there is nothing to return yet ({@code XREAD ... BLOCK 5000 STREAMS jobs $}): what
 * to wait for, for how long, and what to run again each time something changed.
 *
 * <p>The connection's virtual thread runs {@code command}; when the reply is a null array ("nothing yet") it parks
 * until a key in {@code keys} is touched (see {@code KeyWaiters}) or the time is up, and runs it again. The wait holds
 * no lock. A command that waits must therefore be safe to run repeatedly: a read that returns nothing changes nothing
 * that matters, and a {@code >} group read that returns nothing has no effect except creating its consumer.
 *
 * @param keys          the keys whose change can unblock the command
 * @param timeoutMillis how long to wait in total; {@code 0} waits until something arrives (or the client leaves)
 * @param command       what to execute, each time: the original command with anything that depends on "now" fixed -
 *                      {@code $} (the last id when the command arrived) becomes that id, or a new entry would be
 *                      "the last id" for ever and never be delivered
 * @param worthTrying   a cheap, lock-free look at the keys that says {@code false} only when running the command
 *                      would certainly find nothing; asked after every wake-up, so that the many readers woken by an
 *                      entry that is meant for one of them do not all take the write path to find that out
 */
public record BlockingRequest(
        List<String> keys, long timeoutMillis, List<String> command, BooleanSupplier worthTrying) {
}
