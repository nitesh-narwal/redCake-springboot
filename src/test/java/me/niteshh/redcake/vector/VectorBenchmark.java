package me.niteshh.redcake.vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Measures the index itself - no network, no commands. Not a test (surefire does not pick it up); run it with
 * <pre>
 *   scripts/vector-benchmark.sh [dim] [count] [NOQUANT|Q8|BIN] [queries]
 * </pre>
 * which compiles nothing new and starts this class with and without the SIMD module.
 *
 * <p>The data are mixtures of Gaussian clusters, noisier than the toy sets of the unit tests, so neighbours are not
 * obvious. Recall is reported two ways: against an exhaustive scan of the <em>same</em> stored codes (how well the
 * graph finds what exists) and against exact float32 cosine on the original vectors (what a user sees, including the
 * loss from quantization).
 */
public final class VectorBenchmark {

    private VectorBenchmark() {
    }

    private static float[][] generate(int count, int dim, int clusters, double spread, long seed) {
        Random random = new Random(seed);
        float[][] centres = new float[clusters][dim];
        for (float[] centre : centres) {
            for (int i = 0; i < dim; i++) {
                centre[i] = (float) random.nextGaussian();
            }
        }
        float[][] out = new float[count][dim];
        for (int n = 0; n < count; n++) {
            float[] centre = centres[random.nextInt(clusters)];
            for (int i = 0; i < dim; i++) {
                out[n][i] = centre[i] + (float) (spread * random.nextGaussian());
            }
        }
        return out;
    }

    private static List<Integer> trueTop(float[][] vectors, float[] query, int k) {
        double qn = 0;
        for (float x : query) {
            qn += (double) x * x;
        }
        qn = Math.sqrt(qn);
        PriorityQueue<double[]> heap = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        for (int i = 0; i < vectors.length; i++) {
            float[] v = vectors[i];
            double dot = 0;
            double vn = 0;
            for (int d = 0; d < v.length; d++) {
                dot += (double) v[d] * query[d];
                vn += (double) v[d] * v[d];
            }
            double cosine = dot / (Math.sqrt(vn) * qn);
            if (heap.size() < k) {
                heap.add(new double[]{cosine, i});
            } else if (cosine > heap.peek()[0]) {
                heap.poll();
                heap.add(new double[]{cosine, i});
            }
        }
        List<Integer> out = new ArrayList<>();
        for (double[] e : heap) {
            out.add((int) e[1]);
        }
        return out;
    }

    private static double percentile(long[] sortedNanos, double p) {
        return sortedNanos[(int) Math.min(sortedNanos.length - 1, Math.floor(p * sortedNanos.length))] / 1000.0;
    }

    public static void main(String[] args) throws Exception {
        int dim = args.length > 0 ? Integer.parseInt(args[0]) : 384;
        int count = args.length > 1 ? Integer.parseInt(args[1]) : 50_000;
        Quantization quantization = args.length > 2 ? Quantization.fromOption(args[2]) : Quantization.Q8;
        int queryCount = args.length > 3 ? Integer.parseInt(args[3]) : 500;
        int m = 16;
        int efConstruction = 200;

        System.out.printf("kernel=%s dim=%d count=%d quantization=%s M=%d efConstruction=%d cores=%d%n",
                Kernels.active().name(), dim, count, quantization, m, efConstruction,
                Runtime.getRuntime().availableProcessors());

        float[][] all = generate(count + queryCount, dim, 200, 0.8, 42);
        float[][] vectors = Arrays.copyOfRange(all, 0, count);
        float[][] queries = Arrays.copyOfRange(all, count, count + queryCount);

        // ---- build -----------------------------------------------------------------------------------
        VectorSet set = new VectorSet(dim, quantization, m, efConstruction);
        Runtime runtime = Runtime.getRuntime();
        System.gc();
        long heapBefore = runtime.totalMemory() - runtime.freeMemory();
        long t0 = System.nanoTime();
        for (int i = 0; i < count; i++) {
            set.add("item:" + i, set.encode(vectors[i]), null);
            if ((i + 1) % 10_000 == 0) {
                double seconds = (System.nanoTime() - t0) / 1e9;
                System.out.printf("  inserted %,d in %.1f s (%.0f/s so far)%n", i + 1, seconds, (i + 1) / seconds);
            }
        }
        double buildSeconds = (System.nanoTime() - t0) / 1e9;
        System.gc();
        long heapAfter = runtime.totalMemory() - runtime.freeMemory();
        System.out.printf("BUILD  %.0f inserts/s single thread (%.2f ms each)%n", count / buildSeconds,
                1000 * buildSeconds / count);
        System.out.printf("MEMORY %.0f bytes/vector estimated, %.0f measured on the heap (raw float32 would be %d)%n",
                (double) set.estimatedBytes() / count, (double) (heapAfter - heapBefore) / count, 4 * dim);

        // ---- exact references --------------------------------------------------------------------------
        int truthQueries = Math.min(queryCount, 100);
        List<List<String>> scanTruth = new ArrayList<>();
        for (int q = 0; q < truthQueries; q++) {
            List<String> names = new ArrayList<>();
            for (VectorSet.Hit hit : set.search(set.encode(queries[q]), 10, 100, -1, true)) {
                names.add(hit.element());
            }
            scanTruth.add(names);
        }
        List<List<Integer>> floatTruth = new ArrayList<>();
        for (int q = 0; q < truthQueries; q++) {
            floatTruth.add(trueTop(vectors, queries[q], 10));
        }
        long e0 = System.nanoTime();
        for (int q = 0; q < 20; q++) {
            set.search(set.encode(queries[q]), 10, 100, -1, true);
        }
        System.out.printf("EXACT  full scan: %.1f ms per query (%d vectors)%n", (System.nanoTime() - e0) / 20e6, count);

        // ---- search: latency and accuracy per ef ------------------------------------------------------------
        System.out.println("SEARCH single thread, COUNT 10");
        System.out.println("   ef   p50 us   p99 us      QPS   recall(vs scan)  recall(vs float32)");
        for (int ef : new int[]{16, 50, 100, 200, 400}) {
            for (int warm = 0; warm < 200; warm++) {
                set.search(set.encode(queries[warm % queryCount]), 10, ef, -1, false);
            }
            long[] nanos = new long[queryCount * 2];
            double scanRecall = 0;
            double floatRecall = 0;
            long start = System.nanoTime();
            for (int i = 0; i < nanos.length; i++) {
                int q = i % queryCount;
                long a = System.nanoTime();
                List<VectorSet.Hit> hits = set.search(set.encode(queries[q]), 10, ef, -1, false);
                nanos[i] = System.nanoTime() - a;
                if (i < truthQueries) {
                    long same = hits.stream().filter(h -> scanTruth.get(q).contains(h.element())).count();
                    scanRecall += same / 10.0;
                    long sameFloat = hits.stream().filter(h ->
                            floatTruth.get(q).contains(Integer.parseInt(h.element().substring(5)))).count();
                    floatRecall += sameFloat / 10.0;
                }
            }
            double seconds = (System.nanoTime() - start) / 1e9;
            Arrays.sort(nanos);
            System.out.printf("  %4d %8.0f %8.0f %8.0f   %14.3f  %18.3f%n", ef, percentile(nanos, 0.5),
                    percentile(nanos, 0.99), nanos.length / seconds, scanRecall / truthQueries, floatRecall / truthQueries);
        }

        // ---- search: scaling with threads ---------------------------------------------------------------
        System.out.println("SCALING COUNT 10, ef 100");
        for (int threads : new int[]{1, 2, 4}) {
            AtomicLong done = new AtomicLong();
            long until = System.nanoTime() + 3_000_000_000L;
            Thread[] pool = new Thread[threads];
            for (int t = 0; t < threads; t++) {
                int seed = t;
                pool[t] = new Thread(() -> {
                    Random random = new Random(seed);
                    long local = 0;
                    while (System.nanoTime() < until) {
                        set.search(set.encode(queries[random.nextInt(queryCount)]), 10, 100, -1, false);
                        local++;
                    }
                    done.addAndGet(local);
                });
                pool[t].start();
            }
            for (Thread thread : pool) {
                thread.join();
            }
            System.out.printf("  %d threads: %.0f searches/s%n", threads, done.get() / 3.0);
        }

        // ---- searching while inserting -----------------------------------------------------------------------
        {
            AtomicLong searched = new AtomicLong();
            AtomicLong inserted = new AtomicLong();
            long until = System.nanoTime() + 3_000_000_000L;
            Thread writer = new Thread(() -> {
                Random random = new Random(5);
                while (System.nanoTime() < until) {
                    int i = random.nextInt(count);
                    set.add("churn:" + inserted.get() % 2000, set.encode(vectors[i]), null);
                    inserted.incrementAndGet();
                }
            });
            Thread[] readers = new Thread[2];
            for (int t = 0; t < readers.length; t++) {
                int seed = t;
                readers[t] = new Thread(() -> {
                    Random random = new Random(seed);
                    while (System.nanoTime() < until) {
                        set.search(set.encode(queries[random.nextInt(queryCount)]), 10, 100, -1, false);
                        searched.incrementAndGet();
                    }
                });
            }
            writer.start();
            for (Thread r : readers) {
                r.start();
            }
            writer.join();
            for (Thread r : readers) {
                r.join();
            }
            System.out.printf("MIXED  1 writer + 2 readers for 3 s: %.0f inserts/s, %.0f searches/s%n",
                    inserted.get() / 3.0, searched.get() / 3.0);
        }

        // ---- dump and restore versus inserting again -------------------------------------------------------------
        long d0 = System.nanoTime();
        VectorSet.Dump dump = set.dump();
        long d1 = System.nanoTime();
        VectorSet copy = VectorSet.beginRestore(dump.header());
        for (VectorSet.Chunk chunk : dump.chunks()) {
            copy.restoreChunk(chunk.firstId(), chunk.data());
        }
        copy.finishRestore();
        long d2 = System.nanoTime();
        long bytes = dump.chunks().stream().mapToLong(c -> c.data().length).sum();
        System.out.printf("DUMP   %.2f s to dump, %.2f s to load %,d vectors (%.0f MB) - inserting them again took %.1f s%n",
                (d1 - d0) / 1e9, (d2 - d1) / 1e9, set.size(), bytes / 1e6, buildSeconds);
    }
}
