package me.niteshh.redcake.vector;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;

/**
 * Kernel on the JDK's incubating Vector API. On a machine with AVX-512 one int8 dot product of 768 dimensions
 * measured about 8x faster than the portable loop when the vectors are cache-resident (60 ns against 500 ns) and
 * about 3.5x faster when they are fetched from memory at random, which is what a graph search does.
 *
 * <p>This class is only ever loaded through {@link Kernels}, and only when the JVM was started with
 * {@code --add-modules jdk.incubator.vector}; referencing it any other way fails with
 * {@code NoClassDefFoundError}.
 *
 * <p>The int8 sum is exact and independent of the order of additions, so this kernel and {@link ScalarKernel}
 * agree bit for bit on quantized data. The float sums differ in the last bits.
 */
final class SimdKernel implements DistanceKernel {

    private static final VectorSpecies<Float> FLOATS = FloatVector.SPECIES_PREFERRED;
    private static final VectorSpecies<Integer> INTS = IntVector.SPECIES_PREFERRED;
    /** A byte species with exactly as many lanes as {@link #INTS}, so one widening conversion fills one int vector. */
    private static final VectorSpecies<Byte> BYTES =
            INTS.length() >= 8 ? VectorSpecies.of(byte.class, VectorShape.forBitSize(INTS.length() * 8)) : null;

    /** @return whether this machine has vectors wide enough (at least 256 bits) for the kernel to beat the scalar loop */
    static boolean supported() {
        return BYTES != null && FLOATS.length() >= 8;
    }

    @Override
    public int dotInt8(byte[] a, int aOff, byte[] b, int bOff, int n) {
        final int lanes = INTS.length();
        IntVector acc0 = IntVector.zero(INTS);
        IntVector acc1 = IntVector.zero(INTS);
        int i = 0;
        for (; i + 2 * lanes <= n; i += 2 * lanes) {
            IntVector x0 = widen(ByteVector.fromArray(BYTES, a, aOff + i));
            IntVector y0 = widen(ByteVector.fromArray(BYTES, b, bOff + i));
            IntVector x1 = widen(ByteVector.fromArray(BYTES, a, aOff + i + lanes));
            IntVector y1 = widen(ByteVector.fromArray(BYTES, b, bOff + i + lanes));
            acc0 = acc0.add(x0.mul(y0));
            acc1 = acc1.add(x1.mul(y1));
        }
        for (; i + lanes <= n; i += lanes) {
            IntVector x = widen(ByteVector.fromArray(BYTES, a, aOff + i));
            IntVector y = widen(ByteVector.fromArray(BYTES, b, bOff + i));
            acc0 = acc0.add(x.mul(y));
        }
        int sum = acc0.add(acc1).reduceLanes(VectorOperators.ADD);
        for (; i < n; i++) {
            sum += a[aOff + i] * b[bOff + i];
        }
        return sum;
    }

    private static IntVector widen(ByteVector bytes) {
        return bytes.convertShape(VectorOperators.B2I, INTS, 0).reinterpretAsInts();
    }

    @Override
    public float dotFloat(float[] a, int aOff, float[] b, int bOff, int n) {
        final int lanes = FLOATS.length();
        FloatVector acc0 = FloatVector.zero(FLOATS);
        FloatVector acc1 = FloatVector.zero(FLOATS);
        FloatVector acc2 = FloatVector.zero(FLOATS);
        FloatVector acc3 = FloatVector.zero(FLOATS);
        int i = 0;
        for (; i + 4 * lanes <= n; i += 4 * lanes) {
            acc0 = FloatVector.fromArray(FLOATS, a, aOff + i).fma(FloatVector.fromArray(FLOATS, b, bOff + i), acc0);
            acc1 = FloatVector.fromArray(FLOATS, a, aOff + i + lanes)
                    .fma(FloatVector.fromArray(FLOATS, b, bOff + i + lanes), acc1);
            acc2 = FloatVector.fromArray(FLOATS, a, aOff + i + 2 * lanes)
                    .fma(FloatVector.fromArray(FLOATS, b, bOff + i + 2 * lanes), acc2);
            acc3 = FloatVector.fromArray(FLOATS, a, aOff + i + 3 * lanes)
                    .fma(FloatVector.fromArray(FLOATS, b, bOff + i + 3 * lanes), acc3);
        }
        for (; i + lanes <= n; i += lanes) {
            acc0 = FloatVector.fromArray(FLOATS, a, aOff + i).fma(FloatVector.fromArray(FLOATS, b, bOff + i), acc0);
        }
        float sum = acc0.add(acc1).add(acc2.add(acc3)).reduceLanes(VectorOperators.ADD);
        for (; i < n; i++) {
            sum += a[aOff + i] * b[bOff + i];
        }
        return sum;
    }

    @Override
    public String name() {
        return "simd (" + FLOATS.length() + " float lanes, " + FLOATS.vectorBitSize() + "-bit)";
    }
}
