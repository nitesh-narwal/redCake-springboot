package me.niteshh.redcake.vector;

/**
 * Portable kernel in plain Java. The two loops are written the way the JIT likes them: the int8 loop is left simple
 * so the compiler can auto-vectorize it (a hand-unrolled version measured slower), the float loop uses eight
 * independent accumulators because floating-point addition is not associative and the JIT will not reorder it.
 */
final class ScalarKernel implements DistanceKernel {

    @Override
    public int dotInt8(byte[] a, int aOff, byte[] b, int bOff, int n) {
        int sum = 0;
        for (int i = 0; i < n; i++) {
            sum += a[aOff + i] * b[bOff + i];
        }
        return sum;
    }

    @Override
    public float dotFloat(float[] a, int aOff, float[] b, int bOff, int n) {
        float s0 = 0, s1 = 0, s2 = 0, s3 = 0, s4 = 0, s5 = 0, s6 = 0, s7 = 0;
        int i = 0;
        for (; i + 8 <= n; i += 8) {
            s0 += a[aOff + i] * b[bOff + i];
            s1 += a[aOff + i + 1] * b[bOff + i + 1];
            s2 += a[aOff + i + 2] * b[bOff + i + 2];
            s3 += a[aOff + i + 3] * b[bOff + i + 3];
            s4 += a[aOff + i + 4] * b[bOff + i + 4];
            s5 += a[aOff + i + 5] * b[bOff + i + 5];
            s6 += a[aOff + i + 6] * b[bOff + i + 6];
            s7 += a[aOff + i + 7] * b[bOff + i + 7];
        }
        for (; i < n; i++) {
            s0 += a[aOff + i] * b[bOff + i];
        }
        return ((s0 + s1) + (s2 + s3)) + ((s4 + s5) + (s6 + s7));
    }

    @Override
    public String name() {
        return "scalar";
    }
}
