package me.niteshh.redcake.vector;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The two kernels must agree with a naive reference for every length, including the tails around vector widths. */
class KernelsTest {

    private static final int[] LENGTHS = {0, 1, 2, 7, 8, 9, 15, 16, 17, 31, 32, 33, 63, 64, 65, 100, 127, 128, 384, 768, 1000, 1536, 4097};

    private static int referenceInt8(byte[] a, int ao, byte[] b, int bo, int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            sum += (long) a[ao + i] * b[bo + i];
        }
        return (int) sum;
    }

    private static double referenceFloat(float[] a, int ao, float[] b, int bo, int n) {
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += (double) a[ao + i] * b[bo + i];
        }
        return sum;
    }

    private static void checkInt8(DistanceKernel kernel) {
        Random random = new Random(7);
        for (int n : LENGTHS) {
            byte[] a = new byte[n + 13];
            byte[] b = new byte[n + 29];
            for (int round = 0; round < 3; round++) {
                for (int i = 0; i < a.length; i++) {
                    a[i] = (byte) (random.nextInt(255) - 127);
                }
                for (int i = 0; i < b.length; i++) {
                    b[i] = (byte) (random.nextInt(255) - 127);
                }
                // offsets that are not aligned to anything, to catch lane/offset mixups
                assertEquals(referenceInt8(a, 5, b, 11, n), kernel.dotInt8(a, 5, b, 11, n), kernel.name() + " n=" + n);
            }
        }
    }

    private static void checkFloat(DistanceKernel kernel) {
        Random random = new Random(11);
        for (int n : LENGTHS) {
            float[] a = new float[n + 13];
            float[] b = new float[n + 29];
            for (int i = 0; i < a.length; i++) {
                a[i] = (float) random.nextGaussian();
            }
            for (int i = 0; i < b.length; i++) {
                b[i] = (float) random.nextGaussian();
            }
            double expected = referenceFloat(a, 3, b, 9, n);
            assertEquals(expected, kernel.dotFloat(a, 3, b, 9, n), 1e-3 * Math.max(1, Math.sqrt(n)), kernel.name() + " n=" + n);
        }
    }

    @Test
    void scalarKernelMatchesTheReference() {
        checkInt8(Kernels.scalar());
        checkFloat(Kernels.scalar());
    }

    @Test
    void int8ExtremesDoNotOverflow() {
        byte[] a = new byte[16384];
        byte[] b = new byte[16384];
        java.util.Arrays.fill(a, (byte) -127);
        java.util.Arrays.fill(b, (byte) -127);
        assertEquals(16384 * 127 * 127, Kernels.scalar().dotInt8(a, 0, b, 0, a.length));
        DistanceKernel simd = Kernels.simdOrNull();
        if (simd != null) {
            assertEquals(16384 * 127 * 127, simd.dotInt8(a, 0, b, 0, a.length));
        }
    }

    @Test
    void simdKernelMatchesTheReferenceWhenTheJvmHasTheModule() {
        DistanceKernel simd = Kernels.simdOrNull();
        assumeTrue(simd != null, "start the JVM with --add-modules jdk.incubator.vector (the build does for tests)");
        checkInt8(simd);
        checkFloat(simd);
    }

    @Test
    void int8ResultsAreIdenticalAcrossKernels() {
        DistanceKernel simd = Kernels.simdOrNull();
        assumeTrue(simd != null, "SIMD kernel not available");
        Random random = new Random(3);
        byte[] a = new byte[768];
        byte[] b = new byte[768];
        for (int round = 0; round < 200; round++) {
            for (int i = 0; i < 768; i++) {
                a[i] = (byte) (random.nextInt(255) - 127);
                b[i] = (byte) (random.nextInt(255) - 127);
            }
            assertEquals(Kernels.scalar().dotInt8(a, 0, b, 0, 768), simd.dotInt8(a, 0, b, 0, 768));
        }
    }

    @Test
    void theActiveKernelIsChosenAndNamed() {
        assertNotNull(Kernels.active());
        assertNotNull(Kernels.active().name());
    }
}
