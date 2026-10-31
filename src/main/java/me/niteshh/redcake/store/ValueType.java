package me.niteshh.redcake.store;

import me.niteshh.redcake.json.JsonDocument;
import me.niteshh.redcake.vector.VectorSet;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The data types a key can hold. Each type has one Java representation:
 *
 * <ul>
 *   <li>{@code STRING} - {@link String} (a "byte string": every char is one byte 0-255,
 *       see {@code RespParser} for why that makes values binary-safe)</li>
 *   <li>{@code HASH} - {@code LinkedHashMap<String,String>} (keeps insertion order)</li>
 *   <li>{@code LIST} - {@code ArrayList<String>}</li>
 *   <li>{@code SET} - {@code LinkedHashSet<String>}</li>
 *   <li>{@code ZSET} - {@link ZSet} (sorted by score, then member)</li>
 *   <li>{@code VECTORSET} - {@link VectorSet} (named embeddings searchable by similarity; thread-safe by
 *       itself, see {@link KeyValueStore#peek})</li>
 *   <li>{@code JSON} - {@link JsonDocument} (one JSON document, changed and read by path; {@code TYPE} says
 *       {@code ReJSON-RL}, the name RedisJSON uses, so clients that look at it are not surprised)</li>
 * </ul>
 */
public enum ValueType {
    STRING("string"),
    HASH("hash"),
    LIST("list"),
    SET("set"),
    ZSET("zset"),
    VECTORSET("vectorset"),
    JSON("ReJSON-RL");

    private final String redisName;

    ValueType(String redisName) {
        this.redisName = redisName;
    }

    /** @return the name reported by the {@code TYPE} command */
    public String redisName() {
        return redisName;
    }

    /** @return the type of a stored value object */
    public static ValueType of(Object value) {
        return switch (value) {
            case String ignored -> STRING;
            case Map<?, ?> ignored -> HASH;
            case List<?> ignored -> LIST;
            case Set<?> ignored -> SET;
            case ZSet ignored -> ZSET;
            case VectorSet ignored -> VECTORSET;
            case JsonDocument ignored -> JSON;
            default -> throw new IllegalArgumentException(
                    "Unsupported value class: " + value.getClass());
        };
    }
}
