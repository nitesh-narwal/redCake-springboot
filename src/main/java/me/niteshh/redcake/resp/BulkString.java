package me.niteshh.redcake.resp;

/** RESP bulk string reply ({@code $<len>\r\n<bytes>\r\n}): binary-length-prefixed text such as a GET result. */
public record BulkString(String value)
        implements RespValue {
}
