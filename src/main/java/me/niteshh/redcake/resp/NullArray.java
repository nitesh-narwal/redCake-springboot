package me.niteshh.redcake.resp;

/**
 * RESP null array ({@code *-1}). {@code EXEC} returns it when a watched key
 * changed and the transaction was therefore not executed.
 */
public record NullArray() implements RespValue {
}
