package me.niteshh.redcake.store;

/**
 * Outcome of {@link KeyValueStore#set(String, String, SetOptions)}.
 *
 * @param applied       {@code false} when an NX/XX condition prevented the write
 * @param previousValue the value before the call ({@code null} if absent or expired)
 */
public record SetResult(boolean applied, String previousValue) {
}
