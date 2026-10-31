package me.niteshh.redcake.vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Deterministic test vectors and an exact reference search, shared by the vector tests. */
final class VectorTestData {

    private VectorTestData() {
    }

    /** Vectors drawn around a few cluster centres: the structure real embeddings have, and harder for graphs than noise. */
    static float[][] clustered(int count, int dim, int clusters, double spread, long seed) {
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

    static float[][] uniform(int count, int dim, long seed) {
        Random random = new Random(seed);
        float[][] out = new float[count][dim];
        for (float[] row : out) {
            for (int i = 0; i < dim; i++) {
                row[i] = (float) random.nextGaussian();
            }
        }
        return out;
    }

    static String name(int i) {
        return "item:" + i;
    }

    static VectorSet build(float[][] vectors, Quantization quantization, int m, int efConstruction) {
        VectorSet set = new VectorSet(vectors[0].length, quantization, m, efConstruction);
        for (int i = 0; i < vectors.length; i++) {
            set.add(name(i), set.encode(vectors[i]), null);
        }
        return set;
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        return dot / Math.sqrt(na * nb);
    }

    /** The {@code k} element names most similar to {@code query} by true (unquantized) cosine similarity. */
    static List<String> trueTop(float[][] vectors, float[] query, int k) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < vectors.length; i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingDouble((Integer i) -> -cosine(vectors[i], query)));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < k && i < order.size(); i++) {
            out.add(name(order.get(i)));
        }
        return out;
    }

    static double recall(List<VectorSet.Hit> found, List<String> truth) {
        Set<String> expected = new HashSet<>(truth);
        int hit = 0;
        for (VectorSet.Hit h : found) {
            if (expected.contains(h.element())) {
                hit++;
            }
        }
        return truth.isEmpty() ? 1.0 : (double) hit / truth.size();
    }
}
