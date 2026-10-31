package me.niteshh.redcake.vector;

import java.util.Locale;

/**
 * How a vector set stores its embeddings. Every vector is normalized to unit length first (similarity is the
 * cosine), so the choice only trades memory and speed against exactness.
 */
public enum Quantization {

    /** 32-bit floats: exact, 4 bytes per dimension. */
    NOQUANT("fp32", 0),
    /** 8-bit integers plus one scale per vector: 4x smaller and much faster to compare, ~1% score error. The default. */
    Q8("int8", 1),
    /** One bit per dimension (the sign), compared by Hamming distance: 32x smaller, coarse. */
    BIN("bin", 2);

    private final String wireName;
    private final int code;

    Quantization(String wireName, int code) {
        this.wireName = wireName;
        this.code = code;
    }

    /** @return the name reported by {@code VINFO} and {@code VEMB ... RAW} */
    public String wireName() {
        return wireName;
    }

    /** @return the number used in the binary dump format */
    int code() {
        return code;
    }

    /** @return the quantization for a {@code VADD} option word ({@code NOQUANT}, {@code Q8}, {@code BIN}), or {@code null} */
    public static Quantization fromOption(String option) {
        return switch (option.toUpperCase(Locale.ROOT)) {
            case "NOQUANT" -> NOQUANT;
            case "Q8" -> Q8;
            case "BIN" -> BIN;
            default -> null;
        };
    }

    /** @return the quantization stored under {@code code} in the dump format, or {@code null} */
    static Quantization fromCode(int code) {
        for (Quantization quantization : values()) {
            if (quantization.code == code) {
                return quantization;
            }
        }
        return null;
    }
}
