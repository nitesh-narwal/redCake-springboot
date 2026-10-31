package me.niteshh.redcake.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Measures the JSON type on its own, without a server (surefire does not run it: no {@code Test} in its name).
 *
 * <pre>
 *   java -Xmx2g -cp target/test-classes:target/classes:... me.niteshh.redcake.json.JsonBenchmark [dimensions] [records]
 * </pre>
 *
 * It reports, for a record of the kind a machine-learning application stores (a text, an embedding, metadata and a
 * status): how fast the text is parsed and written, what a path operation costs, what a record takes in memory
 * (measured on the heap, against the estimate the store uses for {@code maxmemory}, and against a conventional tree of
 * {@code LinkedHashMap} / {@code ArrayList<Double>}), and how parsing scales with threads.
 */
public final class JsonBenchmark {

    private JsonBenchmark() {
    }

    static String record(Random random, int id, int dimensions, int textWords) {
        StringBuilder text = new StringBuilder("{\"id\":\"doc-").append(id).append("\",\"text\":\"");
        for (int i = 0; i < textWords; i++) {
            text.append("word").append(random.nextInt(1000)).append(' ');
        }
        text.append("\",\"embedding\":[");
        for (int i = 0; i < dimensions; i++) {
            text.append(i > 0 ? "," : "").append((float) random.nextGaussian());
        }
        text.append("],\"meta\":{\"source\":\"web\",\"lang\":\"en\",\"tags\":[\"alpha\",\"beta\",\"gamma\"],\"year\":")
                .append(1990 + random.nextInt(35)).append(",\"score\":").append(random.nextDouble())
                .append("},\"status\":\"new\",\"attempts\":0}");
        return text.toString();
    }

    /** The same document as the tree most JSON libraries build: maps, lists and boxed numbers. */
    static Object conventional(Object node) {
        if (node instanceof JsonObject object) {
            Map<String, Object> map = new LinkedHashMap<>();
            object.forEach((k, v) -> map.put(new String(k), conventional(v)));
            return map;
        }
        if (node instanceof JsonArray array) {
            List<Object> list = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) {
                list.add(conventional(array.get(i)));
            }
            return list;
        }
        if (node instanceof String s) {
            return new String(s);
        }
        return node instanceof Long l ? Long.valueOf(l) : node instanceof Double d ? Double.valueOf(d) : node;
    }

    private static long heap() {
        for (int i = 0; i < 4; i++) {
            System.gc();
        }
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private interface Task {
        void run(int i);
    }

    /** @return operations per second of {@code task}, after a warm-up */
    private static double rate(String name, int warmup, int runs, Task task) {
        for (int i = 0; i < warmup; i++) {
            task.run(i);
        }
        long start = System.nanoTime();
        for (int i = 0; i < runs; i++) {
            task.run(i);
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        double perSecond = runs / seconds;
        System.out.printf("  %-44s %,12.0f /s   %8.2f us each%n", name, perSecond, 1e6 / perSecond);
        return perSecond;
    }

    public static void main(String[] args) throws Exception {
        int dimensions = args.length > 0 ? Integer.parseInt(args[0]) : 384;
        int records = args.length > 1 ? Integer.parseInt(args[1]) : 20_000;
        Random random = new Random(1);
        String text = record(random, 0, dimensions, 300);
        System.out.printf("record: %,d bytes of JSON (%d-dimensional embedding, about 2 KB of text)%n%n", text.length(), dimensions);

        System.out.println("speed, one thread:");
        double parse = rate("parse", 20_000, 60_000, i -> JsonParser.parse(text));
        System.out.printf("  %-44s %,12.0f MB/s%n", "parse throughput", parse * text.length() / 1e6);
        JsonDocument doc = JsonDocument.of(JsonParser.parse(text).value());
        double write = rate("write the whole record", 20_000, 60_000, i -> JsonWriter.toString(doc.root()));
        System.out.printf("  %-44s %,12.0f MB/s%n", "write throughput", write * text.length() / 1e6);
        JsonPath status = JsonPath.parse("$.status");
        JsonPath deep = JsonPath.parse("$.meta.tags[1]");
        JsonPath embedding = JsonPath.parse("$.embedding");
        JsonPath attempts = JsonPath.parse("$.attempts");
        rate("select $.status", 200_000, 2_000_000, i -> doc.select(status));
        rate("select $.meta.tags[1]", 200_000, 2_000_000, i -> doc.select(deep));
        rate("parse the path $.meta.tags[1]", 200_000, 2_000_000, i -> JsonPath.parse("$.meta.tags[1]"));
        rate("set $.status (replace a string)", 200_000, 2_000_000,
                i -> doc.set(status, "s" + (i & 7), 0, JsonDocument.SetMode.ALWAYS));
        rate("numincrby $.attempts", 200_000, 2_000_000, i -> doc.number(attempts, JsonDocument.Arithmetic.ADD, 1L));
        rate("append a number to $.embedding", 200_000, 2_000_000, i -> {
            doc.appendToArrays(embedding, List.of(0.5), 0);
            if ((i & 1023) == 1023) {
                doc.trimArrays(embedding, 0, dimensions - 1L);
            }
        });
        rate("select $.embedding and write it", 20_000, 200_000, i -> JsonWriter.toString(doc.select(embedding).get(0).value()));

        System.out.println("\nmemory, " + records + " records (heap measured after a full GC):");
        Object[] mine = new Object[records];
        long before = heap();
        long estimated = 0;
        for (int i = 0; i < records; i++) {
            JsonDocument d = JsonDocument.of(JsonParser.parse(record(random, i, dimensions, 300)).value());
            mine[i] = d;
            estimated += d.estimatedBytes();
        }
        long measured = heap() - before;
        System.out.printf("  RedCake JSON document        %,8d bytes measured, %,8d estimated for maxmemory (%.0f %%)%n",
                measured / records, estimated / records, 100.0 * estimated / measured);
        Object[] conventional = new Object[records];
        before = heap();
        for (int i = 0; i < records; i++) {
            conventional[i] = conventional(((JsonDocument) mine[i]).root());
        }
        long conventionalBytes = heap() - before;
        System.out.printf("  maps, lists and boxed numbers %,8d bytes measured  (%.1fx the size of the document above)%n",
                conventionalBytes / records, (double) conventionalBytes / measured);
        System.out.printf("  the JSON text itself          %,8d bytes%n", text.length());
        if (mine.length == 0 || conventional.length == 0) {
            System.out.println();
        }

        System.out.println("\nparsing on several threads (the server parses before it takes the write lock):");
        for (int threads : new int[]{1, 2, 4}) {
            AtomicLong done = new AtomicLong();
            long end = System.nanoTime() + 2_000_000_000L;
            List<Thread> pool = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                Thread thread = new Thread(() -> {
                    long count = 0;
                    while (System.nanoTime() < end) {
                        JsonParser.parse(text);
                        count++;
                    }
                    done.addAndGet(count);
                });
                pool.add(thread);
                thread.start();
            }
            for (Thread thread : pool) {
                thread.join();
            }
            System.out.printf("  %d thread(s): %,10.0f parses/s%n", threads, done.get() / 2.0);
        }
    }
}
