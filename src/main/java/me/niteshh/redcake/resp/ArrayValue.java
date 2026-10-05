package me.niteshh.redcake.resp;

import java.util.List;

/**
 * RESP array reply ({@code *<n>\r\n<element>...}).
 *
 * <p>Used by commands that return several values at once, e.g. {@code MGET},
 * {@code KEYS} and {@code COMMAND}. Elements are themselves {@link RespValue}s,
 * so arrays may contain bulk strings, integers, nulls or nested arrays.
 *
 * @param elements the reply elements in order; never {@code null}
 */
public record ArrayValue(List<RespValue> elements) implements RespValue {

    /** Convenience factory that wraps plain strings into bulk-string elements. */
    public static ArrayValue ofBulkStrings(List<String> values) {
        return new ArrayValue(
                values.stream()
                        .<RespValue>map(BulkString::new)
                        .toList()
        );
    }

    /** Shared immutable empty array reply ({@code *0\r\n}). */
    public static ArrayValue empty() {
        return new ArrayValue(List.of());
    }
}
