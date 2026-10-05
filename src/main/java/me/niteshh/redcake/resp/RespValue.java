package me.niteshh.redcake.resp;

/**
 * Closed set of reply types a command may return to a client.
 *
 * <p>The interface is {@code sealed}, so {@link RespWriter} can use an
 * exhaustive {@code switch} and the compiler flags any writer that forgets to
 * handle a newly added reply type.
 */
public sealed interface RespValue
        permits SimpleString,
        BulkString,
        IntegerValue,
        ErrorValue,
        NullValue,
        ArrayValue,
        NullArray {

}
