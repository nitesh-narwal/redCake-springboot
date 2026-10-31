package me.niteshh.redcake.vector;

/**
 * The inner loops of vector search: dot products over the two numeric storage formats. A search spends nearly
 * all of its time here, so there are two interchangeable implementations - {@link ScalarKernel} (plain Java, always
 * available) and {@code SimdKernel} (the JDK's Vector API, used when the JVM was started with
 * {@code --add-modules jdk.incubator.vector}). Implementations are stateless and thread-safe.
 */
public interface DistanceKernel {

    /**
     * Sum of {@code a[aOff+i] * b[bOff+i]} for {@code i < n}. Exact: the products fit 15 bits, so the sum cannot
     * overflow an {@code int} for the supported dimensions (at most 16384).
     */
    int dotInt8(byte[] a, int aOff, byte[] b, int bOff, int n);

    /** Sum of {@code a[aOff+i] * b[bOff+i]} for {@code i < n}. */
    float dotFloat(float[] a, int aOff, float[] b, int bOff, int n);

    /** @return a short description for logs, e.g. {@code "scalar"} or {@code "simd (16 float lanes)"} */
    String name();
}
