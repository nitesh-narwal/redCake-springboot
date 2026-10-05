package me.niteshh.redcake.resp;

/** RESP error reply ({@code -<message>}). {@code RespWriter} adds the {@code ERR } code if the message has none. */
public record ErrorValue(String message)
        implements RespValue {
}
