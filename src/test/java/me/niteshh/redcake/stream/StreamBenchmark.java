package me.niteshh.redcake.stream;

import me.niteshh.redcake.support.RespClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Not a test (surefire does not run it): numbers for ADR 0008, run by {@code scripts/stream-benchmark.sh}.
 *
 * <pre>
 *   StreamBenchmark memory [entries] [redis-host:port]   heap per entry against a TreeMap of String arrays (and Redis)
 *   StreamBenchmark speed  [entries]                     the data structure alone, one thread
 *   StreamBenchmark queue  host port [jobs] [workers]    a job queue over TCP: throughput, then wake-up latency
 * </pre>
 */
public final class StreamBenchmark {

    private StreamBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "memory";
        switch (mode) {
            case "memory" -> memory(args.length > 1 ? Integer.parseInt(args[1]) : 100_000, args.length > 2 ? args[2] : null);
            case "speed" -> speed(args.length > 1 ? Integer.parseInt(args[1]) : 1_000_000);
            case "queue" -> queue(args[1], Integer.parseInt(args[2]),
                    args.length > 3 ? Integer.parseInt(args[3]) : 20_000, args.length > 4 ? Integer.parseInt(args[4]) : 8);
            default -> throw new IllegalArgumentException(mode);
        }
    }

    // ------------------------------------------------------------------------------------------ records

    private static final Random RANDOM = new Random(42);

    /** A small event: three short fields. */
    private static List<String> event(int n) {
        return Arrays.asList("type", n % 3 == 0 ? "click" : "view", "user", "u" + (10_000 + RANDOM.nextInt(50_000)),
                "ts", Long.toString(1_700_000_000_000L + n));
    }

    /** A job for an agent: an id, a prompt of a few hundred bytes, a status and a priority. */
    private static List<String> job(int n) {
        StringBuilder prompt = new StringBuilder();
        while (prompt.length() < 400) {
            prompt.append("word").append(RANDOM.nextInt(1000)).append(' ');
        }
        return Arrays.asList("task", "t-" + n, "payload", prompt.toString(), "status", "queued", "priority", Integer.toString(1 + n % 5));
    }

    // ------------------------------------------------------------------------------------------ memory

    private static long used() {
        for (int i = 0; i < 4; i++) {
            System.gc();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static void memory(int entries, String redis) throws Exception {
        System.out.println("== memory: " + entries + " entries per shape (heap after GC; RedCake's own estimate; a TreeMap of String[]"
                + (redis == null ? "" : "; Redis MEMORY USAGE") + ")");
        for (String shape : List.of("event", "job")) {
            List<List<String>> records = new ArrayList<>(entries);
            for (int n = 0; n < entries; n++) {
                records.add(shape.equals("event") ? event(n) : job(n));
            }
            long data = records.get(0).stream().mapToLong(String::length).sum();

            long before = used();
            Stream stream = new Stream();
            for (int n = 0; n < entries; n++) {
                stream.add(new StreamId(1_700_000_000_000L + n, 0), true, records.get(n), 0, 0);
            }
            long streamBytes = used() - before;
            long estimate = stream.estimatedBytes();
            if (stream.length() != entries) {
                throw new IllegalStateException();
            }
            stream = null;

            before = used();
            TreeMap<StreamId, String[]> naive = new TreeMap<>();
            for (int n = 0; n < entries; n++) {
                List<String> copy = records.get(n);
                String[] fields = new String[copy.size()];
                for (int i = 0; i < fields.length; i++) {
                    // what parsing a command produces: strings with arrays of their own (new String(s) would share the array)
                    fields[i] = new String(copy.get(i).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1),
                            java.nio.charset.StandardCharsets.ISO_8859_1);
                }
                naive.put(new StreamId(1_700_000_000_000L + n, 0), fields);
            }
            long naiveBytes = used() - before;
            if (naive.size() != entries) {
                throw new IllegalStateException();
            }
            naive = null;

            String line = String.format("  %-6s %4d bytes of data per entry | RedCake stream %5d B/entry (estimate %5d) | TreeMap<StreamId,String[]> %5d B/entry",
                    shape, data, streamBytes / entries, estimate / entries, naiveBytes / entries);
            if (redis != null) {
                line += String.format(" | Redis %5d B/entry", redisBytesPerEntry(redis, shape, records));
            }
            System.out.println(line);
        }
    }

    private static long redisBytesPerEntry(String hostPort, String shape, List<List<String>> records) throws Exception {
        int colon = hostPort.lastIndexOf(':');
        try (RespClient redis = new RespClient(hostPort.substring(0, colon), Integer.parseInt(hostPort.substring(colon + 1)))) {
            redis.setTimeout(60_000);
            redis.call("DEL", "bench:" + shape);
            int batch = 500;
            for (int from = 0; from < records.size(); from += batch) {
                int to = Math.min(records.size(), from + batch);
                for (int n = from; n < to; n++) {
                    List<String> command = new ArrayList<>(List.of("XADD", "bench:" + shape, (1_700_000_000_000L + n) + "-0"));
                    command.addAll(records.get(n));
                    redis.send(command.toArray(new String[0]));
                }
                for (int n = from; n < to; n++) {
                    redis.read();
                }
            }
            String usage = redis.call("MEMORY", "USAGE", "bench:" + shape, "SAMPLES", "0");
            redis.call("DEL", "bench:" + shape);
            return Long.parseLong(usage.substring(1)) / records.size();
        }
    }

    // ------------------------------------------------------------------------------------------ speed

    private static void speed(int entries) {
        System.out.println("== speed of the data structure alone, one thread, " + entries + " entries");
        List<List<String>> records = new ArrayList<>();
        for (int n = 0; n < 1000; n++) {
            records.add(job(n));
        }
        for (int round = 0; round < 3; round++) {
            Stream stream = new Stream();
            long start = System.nanoTime();
            for (int n = 0; n < entries; n++) {
                stream.add(null, true, records.get(n % 1000), 0, 1_700_000_000_000L + n / 10);
            }
            double addSeconds = (System.nanoTime() - start) / 1e9;

            start = System.nanoTime();
            long read = 0;
            for (int n = 0; n < entries / 100; n++) {
                StreamId from = new StreamId(1_700_000_000_000L + (long) (Math.random() * entries / 10), 0);
                read += stream.range(from, StreamId.MAX, false, 100).size();
            }
            double rangeSeconds = (System.nanoTime() - start) / 1e9;

            ConsumerGroup group = stream.createGroup("g", StreamId.ZERO, 0);
            Consumer consumer = stream.createConsumer(group, "c", 0);
            start = System.nanoTime();
            long handled = 0;
            while (true) {
                Stream.DeliveryResult result = stream.deliverNew(group, consumer, 10, false, 1);
                if (result.deliveries().isEmpty()) {
                    break;
                }
                List<StreamId> ids = new ArrayList<>();
                for (Stream.Delivery delivery : result.deliveries()) {
                    ids.add(delivery.entry().id());
                }
                handled += stream.ack(group, ids);
            }
            double groupSeconds = (System.nanoTime() - start) / 1e9;

            start = System.nanoTime();
            long trimmed = stream.trimByLength(entries / 2, 0);
            double trimSeconds = (System.nanoTime() - start) / 1e9;
            System.out.printf("  round %d: add %,9.0f entries/s | range of 100: %,8.0f reads/s (%d entries) | deliver 10 + ack %,9.0f entries/s | trim %,d entries in %.1f ms%n",
                    round + 1, entries / addSeconds, entries / 100 / rangeSeconds, read, handled / groupSeconds, trimmed, trimSeconds * 1000);
        }
    }

    // ------------------------------------------------------------------------------------------- queue

    private static final Pattern JOB = Pattern.compile("\"(\\d+-\\d+)\", \\[\"t\", \"(\\d+)\"");

    private static void queue(String host, int port, int jobs, int workers) throws Exception {
        System.out.println("== job queue over TCP: " + jobs + " jobs, " + workers + " workers (XADD, XREADGROUP COUNT 10 BLOCK, XACK)");
        try (RespClient admin = new RespClient(host, port)) {
            admin.setTimeout(30_000);
            admin.call("DEL", "bench:jobs");
            admin.call("XGROUP", "CREATE", "bench:jobs", "g", "0", "MKSTREAM");
        }
        // 1. throughput: the producer writes in batches of 100, the workers drain the queue
        AtomicInteger done = new AtomicInteger();
        AtomicLong producedNanos = new AtomicLong();
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        long start = System.nanoTime();
        List<Thread> threads = new ArrayList<>();
        Thread producer = Thread.ofPlatform().start(() -> {
            try (RespClient client = new RespClient(host, port)) {
                client.setTimeout(30_000);
                for (int from = 0; from < jobs; from += 100) {
                    int to = Math.min(jobs, from + 100);
                    for (int n = from; n < to; n++) {
                        client.send("XADD", "bench:jobs", "*", "t", Long.toString(System.nanoTime()), "n", Integer.toString(n),
                                "payload", "x".repeat(200));
                    }
                    for (int n = from; n < to; n++) {
                        client.read();
                    }
                }
                producedNanos.set(System.nanoTime() - start);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        threads.add(producer);
        for (int w = 0; w < workers; w++) {
            String name = "w" + w;
            threads.add(Thread.ofPlatform().start(() -> {
                try (RespClient client = new RespClient(host, port)) {
                    client.setTimeout(30_000);
                    while (done.get() < jobs) {
                        String reply = client.call("XREADGROUP", "GROUP", "g", name, "COUNT", "10", "BLOCK", "500",
                                "STREAMS", "bench:jobs", ">");
                        Matcher matcher = JOB.matcher(reply);
                        List<String> ack = new ArrayList<>(List.of("XACK", "bench:jobs", "g"));
                        long now = System.nanoTime();
                        while (matcher.find()) {
                            ack.add(matcher.group(1));
                            latencies.add(now - Long.parseLong(matcher.group(2)));
                        }
                        if (ack.size() > 3) {
                            client.call(ack.toArray(new String[0]));
                            done.addAndGet(ack.size() - 3);
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        for (Thread thread : threads) {
            thread.join();
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        System.out.printf("  drain: %,d jobs in %.2f s = %,.0f jobs/s end to end (producer finished after %.2f s); queue time p50 %.1f ms, p99 %.1f ms%n",
                jobs, seconds, jobs / seconds, producedNanos.get() / 1e9, sorted.get(sorted.size() / 2) / 1e6,
                sorted.get((int) (sorted.size() * 0.99)) / 1e6);

        // 2. latency of a wake-up: the workers are all waiting, one job arrives every 2 ms
        int trickle = 1500;
        AtomicInteger seen = new AtomicInteger();
        ConcurrentLinkedQueue<Long> wake = new ConcurrentLinkedQueue<>();
        List<Thread> idle = new ArrayList<>();
        for (int w = 0; w < workers; w++) {
            String name = "idle" + w;
            idle.add(Thread.ofPlatform().start(() -> {
                try (RespClient client = new RespClient(host, port)) {
                    client.setTimeout(30_000);
                    while (seen.get() < trickle) {
                        String reply = client.call("XREADGROUP", "GROUP", "g", name, "COUNT", "1", "BLOCK", "500",
                                "STREAMS", "bench:jobs", ">");
                        Matcher matcher = JOB.matcher(reply);
                        if (matcher.find()) {
                            wake.add(System.nanoTime() - Long.parseLong(matcher.group(2)));
                            client.call("XACK", "bench:jobs", "g", matcher.group(1));
                            seen.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        Thread.sleep(500);
        try (RespClient client = new RespClient(host, port)) {
            client.setTimeout(30_000);
            for (int n = 0; n < trickle; n++) {
                client.call("XADD", "bench:jobs", "*", "t", Long.toString(System.nanoTime()), "n", Integer.toString(n));
                Thread.sleep(2);
            }
        }
        for (Thread thread : idle) {
            thread.join();
        }
        List<Long> woken = new ArrayList<>(wake);
        Collections.sort(woken);
        System.out.printf("  wake-up: one job every 2 ms for %d idle workers: XADD to delivery p50 %.2f ms, p99 %.2f ms, max %.2f ms%n",
                workers, woken.get(woken.size() / 2) / 1e6, woken.get((int) (woken.size() * 0.99)) / 1e6, woken.getLast() / 1e6);
        try (RespClient admin = new RespClient(host, port)) {
            admin.call("DEL", "bench:jobs");
        }
    }
}
