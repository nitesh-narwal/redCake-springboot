package me.niteshh.redcake.resp;

/** RESP simple string reply ({@code +OK}); CR/LF are stripped by the writer. */
public record SimpleString(String value)
        implements RespValue {
}
