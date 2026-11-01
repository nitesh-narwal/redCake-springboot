package me.niteshh.redcake.stream;

/**
 * One entry as a client sees it: its id and the field/value pairs in the order they were written
 * ({@code fields[0]} is the first field name, {@code fields[1]} its value, and so on).
 */
public record StreamEntry(StreamId id, String[] fields) {
}
