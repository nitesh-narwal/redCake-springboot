package me.niteshh.redcake.resp;

public sealed interface RespValue
        permits SimpleString,
        BulkString,
        IntegerValue,
        ErrorValue,
        NullValue {

}
