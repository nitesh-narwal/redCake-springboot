package me.niteshh.redcake.cluster;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tiny {@code key=value} line format used for the data stored in znodes. Human readable in {@code zkCli get},
 * needs no JSON library, and tolerant on read (unknown keys are ignored, so an older node can still read a
 * record written by a newer one).
 */
final class RecordCodec {

    private RecordCodec() {
    }

    static byte[] encode(Map<String, ?> fields) {
        StringBuilder out = new StringBuilder();
        fields.forEach((key, value) -> {
            String text = String.valueOf(value);
            if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("Record value for '" + key + "' contains a line break");
            }
            out.append(key).append('=').append(text).append('\n');
        });
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    static Map<String, String> decode(byte[] data) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (data == null) {
            return fields;
        }
        for (String line : new String(data, StandardCharsets.UTF_8).split("\n")) {
            int equals = line.indexOf('=');
            if (equals > 0) {
                fields.put(line.substring(0, equals).strip(), line.substring(equals + 1).strip());
            }
        }
        return fields;
    }

    static String string(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Record has no '" + key + "'");
        }
        return value;
    }

    static String string(Map<String, String> fields, String key, String fallback) {
        return fields.getOrDefault(key, fallback);
    }

    static long number(Map<String, String> fields, String key, long fallback) {
        String value = fields.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Record field '" + key + "' is not a number: " + value);
        }
    }

    static boolean bool(Map<String, String> fields, String key) {
        return "1".equals(fields.get(key));
    }
}
