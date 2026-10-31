package me.niteshh.redcake.vector;

import lombok.extern.slf4j.Slf4j;

import java.util.Locale;

/**
 * Chooses the {@link DistanceKernel} for this JVM, once.
 *
 * <p>The system property {@code redcake.vector.kernel} selects it: {@code auto} (default: SIMD when the JVM has the
 * {@code jdk.incubator.vector} module and a vector unit of at least 256 bits, otherwise scalar), {@code scalar}, or
 * {@code simd} (fails at startup if it is not available, so a deployment that relies on it notices).
 *
 * <p>The SIMD class is created by reflection on purpose: if it were named in this class's own code the JVM could
 * try to load it - and fail with {@code NoClassDefFoundError} - on a JVM started without the module.
 */
@Slf4j
public final class Kernels {

    private static final String SIMD_CLASS = "me.niteshh.redcake.vector.SimdKernel";
    private static final String PROPERTY = "redcake.vector.kernel";

    /** Holder idiom: the choice is made (and logged) on first use, after this class's logger exists. */
    private static final class Holder {
        static final DistanceKernel ACTIVE = select();
    }

    private Kernels() {
    }

    /** @return the kernel every vector set in this JVM uses */
    public static DistanceKernel active() {
        return Holder.ACTIVE;
    }

    /** @return the portable kernel (for tests and comparisons) */
    static DistanceKernel scalar() {
        return new ScalarKernel();
    }

    /** @return the SIMD kernel, or {@code null} if this JVM cannot run it (no module, or no wide vector unit) */
    static DistanceKernel simdOrNull() {
        if (ModuleLayer.boot().findModule("jdk.incubator.vector").isEmpty()) {
            return null;
        }
        try {
            Class<?> type = Class.forName(SIMD_CLASS);
            if (!(boolean) type.getDeclaredMethod("supported").invoke(null)) {
                return null;
            }
            return (DistanceKernel) type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            log.warn("The SIMD distance kernel is not usable: {}", e.toString());
            return null;
        }
    }

    private static DistanceKernel select() {
        String mode = System.getProperty(PROPERTY, "auto").toLowerCase(Locale.ROOT);
        DistanceKernel chosen;
        switch (mode) {
            case "scalar" -> chosen = new ScalarKernel();
            case "simd" -> {
                chosen = simdOrNull();
                if (chosen == null) {
                    throw new IllegalStateException(PROPERTY + "=simd, but the SIMD kernel is not available: start the JVM"
                            + " with --add-modules jdk.incubator.vector on a CPU with 256-bit vectors or wider");
                }
            }
            case "auto" -> {
                chosen = simdOrNull();
                if (chosen == null) {
                    chosen = new ScalarKernel();
                    log.info("Vector search kernel: scalar. Starting the JVM with --add-modules jdk.incubator.vector "
                            + "makes similarity searches several times faster.");
                } else {
                    log.info("Vector search kernel: {}", chosen.name());
                }
                return chosen;
            }
            default -> throw new IllegalStateException(PROPERTY + " must be auto, scalar or simd, not '" + mode + "'");
        }
        log.info("Vector search kernel: {}", chosen.name());
        return chosen;
    }
}
