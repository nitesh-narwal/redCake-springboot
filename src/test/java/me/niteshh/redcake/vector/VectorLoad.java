package me.niteshh.redcake.vector;

import me.niteshh.redcake.support.RespClient;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Black-box load generator for a running server (see {@code scripts/vector-benchmark.sh}): bulk-loads vectors with
 * pipelined {@code VADD}, measures {@code VSIM} throughput and latency with several clients, and measures what a
 * stream of {@code VADD} does to the latency of an ordinary {@code SET} issued by somebody else - the cost of
 * sharing the server's single write lock.
 *
 * <pre>  VectorLoad host port [dim] [count]</pre>
 */
public final class VectorLoad {

    private VectorLoad() {
    }

    private static String blob(float[] values) {
        ByteBuffer buffer = ByteBuffer.allocate(4 * values.length).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : values) {
            buffer.putFloat(v);
        }
        return new String(buffer.array(), StandardCharsets.ISO_8859_1);
    }

    private static float[][] generate(int count, int dim, long seed) {
        Random random = new Random(seed);
        float[][] centres = new float[200][dim];
        for (float[] centre : centres) {
            for (int i = 0; i < dim; i++) {
                centre[i] = (float) random.nextGaussian();
            }
        }
        float[][] out = new float[count][dim];
        for (float[] row : out) {
            float[] centre = centres[random.nextInt(centres.length)];
            for (int i = 0; i < dim; i++) {
                row[i] = centre[i] + 0.8f * (float) random.nextGaussian();
            }
        }
        return out;
    }

    private static String pct(long[] sortedNanos, double p) {
        return String.format("%.0f", sortedNanos[(int) Math.min(sortedNanos.length - 1, p * sortedNanos.length)] / 1000.0);
    }

    public static void main(String[] args) throws Exception {
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        int dim = args.length > 2 ? Integer.parseInt(args[2]) : 384;
        int count = args.length > 3 ? Integer.parseInt(args[3]) : 30_000;
        float[][] vectors = generate(count + 1000, dim, 7);
        String[] blobs = new String[vectors.length];
        for (int i = 0; i < vectors.length; i++) {
            blobs[i] = blob(vectors[i]);
        }

        // ---- bulk load, pipelined ---------------------------------------------------------------------------
        try (RespClient c = new RespClient(host, port)) {
            c.call("DEL", "bench");
            int batch = 32;
            long t0 = System.nanoTime();
            for (int i = 0; i < count; i += batch) {
                int end = Math.min(count, i + batch);
                for (int j = i; j < end; j++) {
                    c.send("VADD", "bench", "FP32", blobs[j], "item:" + j);
                }
                for (int j = i; j < end; j++) {
                    String reply = c.read();
                    if (!":1".equals(reply)) {
                        throw new IllegalStateException(reply);
                    }
                }
            }
            double seconds = (System.nanoTime() - t0) / 1e9;
            System.out.printf("LOAD   %,d vectors of %d dims in %.1f s = %.0f VADD/s over one connection%n",
                    count, dim, seconds, count / seconds);
        }

        // ---- VSIM with several clients ---------------------------------------------------------------------------
        System.out.println("VSIM   COUNT 10, EF 100 (default), clients each send one command and wait for the reply");
        for (int clients : new int[]{1, 2, 4, 8}) {
            AtomicLong total = new AtomicLong();
            List<long[]> samples = new ArrayList<>();
            long until = System.nanoTime() + 4_000_000_000L;
            Thread[] threads = new Thread[clients];
            for (int t = 0; t < clients; t++) {
                int seed = t;
                long[] mine = new long[200_000];
                samples.add(mine);
                threads[t] = new Thread(() -> {
                    try (RespClient c = new RespClient(host, port)) {
                        Random random = new Random(seed);
                        int n = 0;
                        while (System.nanoTime() < until) {
                            long a = System.nanoTime();
                            String reply = c.call("VSIM", "bench", "FP32", blobs[count + random.nextInt(1000)], "COUNT", "10");
                            long b = System.nanoTime();
                            if (!reply.startsWith("[")) {
                                throw new IllegalStateException(reply);
                            }
                            if (n < mine.length) {
                                mine[n++] = b - a;
                            }
                            total.incrementAndGet();
                        }
                        mine[mine.length - 1] = n;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                threads[t].start();
            }
            for (Thread thread : threads) {
                thread.join();
            }
            List<Long> all = new ArrayList<>();
            for (long[] mine : samples) {
                int n = (int) mine[mine.length - 1];
                for (int i = 0; i < n; i++) {
                    all.add(mine[i]);
                }
            }
            long[] sorted = all.stream().mapToLong(Long::longValue).sorted().toArray();
            System.out.printf("  %d clients: %6.0f VSIM/s   p50 %s us   p99 %s us%n", clients, total.get() / 4.0,
                    pct(sorted, 0.5), pct(sorted, 0.99));
        }

        // ---- ingestion with several connections ------------------------------------------------------------------------
        System.out.println("INGEST VADD with several clients, each sends one command and waits for the reply");
        for (int clients : new int[]{1, 2, 4}) {
            AtomicLong done = new AtomicLong();
            long until = System.nanoTime() + 4_000_000_000L;
            Thread[] threads = new Thread[clients];
            for (int t = 0; t < clients; t++) {
                int id = t;
                threads[t] = new Thread(() -> {
                    try (RespClient c = new RespClient(host, port)) {
                        Random random = new Random(id);
                        long n = 0;
                        while (System.nanoTime() < until) {
                            c.call("VADD", "bench", "FP32", blobs[random.nextInt(count)], "ingest:" + id + ":" + n++ % 2000);
                            done.incrementAndGet();
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                threads[t].start();
            }
            for (Thread thread : threads) {
                thread.join();
            }
            System.out.printf("  %d clients: %6.0f VADD/s%n", clients, done.get() / 4.0);
        }

        // ---- what does a VADD stream do to SET? ---------------------------------------------------------------------
        System.out.println("SET    latency of ordinary writes (one client) while another client streams VADD");
        for (boolean withVadd : new boolean[]{false, true}) {
            AtomicBoolean stop = new AtomicBoolean();
            AtomicLong added = new AtomicLong();
            Thread writer = null;
            if (withVadd) {
                writer = new Thread(() -> {
                    try (RespClient c = new RespClient(host, port)) {
                        Random random = new Random(3);
                        while (!stop.get()) {
                            long n = added.getAndIncrement();
                            c.call("VADD", "bench", "FP32", blobs[random.nextInt(count)], "churn:" + n % 3000);
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                writer.start();
                Thread.sleep(300);
            }
            long[] nanos = new long[20_000];
            long begin = System.nanoTime();
            long addedBefore = added.get();
            try (RespClient c = new RespClient(host, port)) {
                for (int i = 0; i < nanos.length; i++) {
                    long a = System.nanoTime();
                    c.call("SET", "k" + (i % 100), "value");
                    nanos[i] = System.nanoTime() - a;
                }
            }
            double elapsed = (System.nanoTime() - begin) / 1e9;
            double vaddRate = (added.get() - addedBefore) / elapsed;
            stop.set(true);
            if (writer != null) {
                writer.join();
            }
            Arrays.sort(nanos);
            System.out.printf("  %-22s SET p50 %s us   p99 %s us   p99.9 %s us   max %s us%s%n",
                    withVadd ? "with VADD streaming:" : "alone:", pct(nanos, 0.5), pct(nanos, 0.99), pct(nanos, 0.999),
                    pct(nanos, 1.0), withVadd ? String.format("   (%.0f VADD/s meanwhile)", vaddRate) : "");
        }
    }
}
