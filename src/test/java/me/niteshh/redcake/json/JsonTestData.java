package me.niteshh.redcake.json;

import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Random documents and paths shared by the JSON tests. */
final class JsonTestData {

    private JsonTestData() {
    }

    /** The same text for the same value whatever the order of the members of an object. */
    static String canonical(Object value) {
        if (value instanceof JsonObject object) {
            List<String> keys = object.keyList();
            Collections.sort(keys);
            StringBuilder out = new StringBuilder("{");
            for (String key : keys) {
                out.append(out.length() > 1 ? "," : "").append(JsonWriter.quote(key)).append(':').append(canonical(object.get(key)));
            }
            return out.append('}').toString();
        }
        if (value instanceof JsonArray array) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < array.size(); i++) {
                out.append(i > 0 ? "," : "").append(canonical(array.get(i)));
            }
            return out.append(']').toString();
        }
        if (value instanceof Number number) {
            return Double.toString(number.doubleValue());
        }
        return JsonWriter.toString(value);
    }

    static Object randomDocument(Random random, int depth) {
        String[] names = {"a", "b", "c", "d"};
        int kind = depth >= 3 ? random.nextInt(4) : random.nextInt(7);
        switch (kind) {
            case 0:
                return (long) random.nextInt(5);
            case 1:
                return names[random.nextInt(4)] + random.nextInt(3);
            case 2:
                return random.nextBoolean();
            case 3:
                return random.nextInt(3) * 0.5;
            case 4:
            case 5: {
                JsonObject object = new JsonObject();
                int n = random.nextInt(4);
                for (int i = 0; i < n; i++) {
                    object.put(names[random.nextInt(4)], randomDocument(random, depth + 1));
                }
                return object;
            }
            default: {
                JsonArray array = new JsonArray();
                int n = random.nextInt(5);
                for (int i = 0; i < n; i++) {
                    array.add(randomDocument(random, depth + 1));
                }
                return array;
            }
        }
    }

    /** A path that follows the document down (so it selects something), with some steps turned into wildcards and more. */
    static String pathInto(Random random, Object document) {
        StringBuilder path = new StringBuilder("$");
        Object node = document;
        int steps = 1 + random.nextInt(4);
        for (int i = 0; i < steps; i++) {
            if (node instanceof JsonObject object && !object.isEmpty()) {
                List<String> keys = object.keyList();
                String key = keys.get(random.nextInt(keys.size()));
                // (a union of names is left out: Jayway answers it with one object, RFC 9535 with the values)
                switch (random.nextInt(5)) {
                    case 0 -> path.append('.').append(key);
                    case 1 -> path.append("['").append(key).append("']");
                    case 2 -> path.append(".*");
                    case 3 -> path.append("..").append(key);
                    default -> path.append(i == steps - 1 ? "..*" : ".*");
                }
                node = object.get(key);
            } else if (node instanceof JsonArray array && array.size() > 0) {
                int at = random.nextInt(array.size());
                switch (random.nextInt(7)) {
                    case 0 -> path.append('[').append(at).append(']');
                    case 1 -> path.append('[').append(at - array.size()).append(']');
                    case 2 -> path.append("[*]");
                    case 3 -> path.append('[').append(at).append(':').append(Math.min(array.size(), at + 1 + random.nextInt(3))).append(']');
                    case 4 -> path.append("[:").append(1 + random.nextInt(array.size())).append(']');
                    case 5 -> path.append('[').append(at).append(',').append(random.nextInt(array.size())).append(']');
                    default -> path.append("[-").append(1 + random.nextInt(array.size())).append(":]");
                }
                node = array.get(at);
            } else {
                break;
            }
        }
        return path.toString();
    }

    static String randomPath(Random random) {
        String[] names = {"a", "b", "c", "d"};
        StringBuilder path = new StringBuilder("$");
        int steps = 1 + random.nextInt(4);
        for (int i = 0; i < steps; i++) {
            switch (random.nextInt(11)) {
                case 0, 1 -> path.append('.').append(names[random.nextInt(4)]);
                case 2 -> path.append("['").append(names[random.nextInt(4)]).append("']");
                case 3 -> path.append('[').append(random.nextInt(4)).append(']');
                case 4 -> path.append('[').append(-1 - random.nextInt(3)).append(']');
                case 5 -> path.append("[*]");
                case 6 -> path.append(".*");
                case 7 -> path.append("..").append(names[random.nextInt(4)]);
                case 8 -> path.append(i == steps - 1 ? "..*" : ".*");
                case 9 -> path.append('[').append(random.nextInt(3)).append(':').append(1 + random.nextInt(4)).append(']');
                default -> path.append('[').append(random.nextInt(3)).append(',').append(random.nextInt(3)).append(']');
            }
        }
        return path.toString();
    }
}
