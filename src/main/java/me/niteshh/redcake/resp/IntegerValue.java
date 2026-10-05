package me.niteshh.redcake.resp;

/** RESP integer reply ({@code :<n>}), e.g. a counter or a 0/1 flag. */
public record IntegerValue(long value)
        implements RespValue {
}
