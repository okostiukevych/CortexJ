package io.cortexj.convert.gguf.internal;

/**
 * Deterministic IEEE 754 half-precision bit conversion (round-to-nearest-even),
 * pure Java 8, no external dependencies.
 */
public final class Float16 {

    private Float16() {
    }

    public static short toHalf(float value) {
        int bits = Float.floatToIntBits(value);
        int sign = (bits >>> 16) & 0x8000;
        int valueBits = bits & 0x7FFFFFFF;

        if (valueBits == 0x7F800000) {
            return (short) (sign | 0x7C00);
        }
        if (valueBits > 0x7F800000) {
            // NaN: keep a representative mantissa bit
            return (short) (sign | 0x7C00 | 0x0200);
        }
        int exponent = (valueBits >>> 23) - 127 + 15;
        int mantissa = valueBits & 0x007FFFFF;
        if (exponent >= 0x1F) {
            // overflow -> infinity
            return (short) (sign | 0x7C00);
        }
        if (exponent <= 0) {
            if (exponent < -10) {
                // too small -> signed zero
                return (short) sign;
            }
            // subnormal half
            mantissa |= 0x00800000;
            int shift = 14 - exponent;
            int halfMantissa = mantissa >> shift;
            int remainder = mantissa & ((1 << shift) - 1);
            int halfway = 1 << (shift - 1);
            if (remainder > halfway || (remainder == halfway && (halfMantissa & 1) == 1)) {
                halfMantissa++;
            }
            return (short) (sign | halfMantissa);
        }
        int halfMantissa = mantissa >>> 13;
        int remainder = mantissa & 0x1FFF;
        if (remainder > 0x1000 || (remainder == 0x1000 && (halfMantissa & 1) == 1)) {
            halfMantissa++;
            if (halfMantissa == 0x0400) {
                halfMantissa = 0;
                exponent++;
                if (exponent >= 0x1F) {
                    return (short) (sign | 0x7C00);
                }
            }
        }
        return (short) (sign | (exponent << 10) | halfMantissa);
    }

    public static float fromHalf(short half) {
        int bits = half & 0xFFFF;
        int sign = (bits & 0x8000) << 16;
        int exponent = (bits >>> 10) & 0x1F;
        int mantissa = bits & 0x03FF;

        if (exponent == 0) {
            if (mantissa == 0) {
                return Float.intBitsToFloat(sign);
            }
            // subnormal half -> normalized float
            exponent = 127 - 15 + 1;
            while ((mantissa & 0x0400) == 0) {
                mantissa <<= 1;
                exponent--;
            }
            mantissa &= 0x03FF;
            return Float.intBitsToFloat(sign | (exponent << 23) | (mantissa << 13));
        }
        if (exponent == 0x1F) {
            if (mantissa == 0) {
                return Float.intBitsToFloat(sign | 0x7F800000);
            }
            return Float.intBitsToFloat(sign | 0x7F800000 | (mantissa << 13));
        }
        return Float.intBitsToFloat(sign | ((exponent - 15 + 127) << 23) | (mantissa << 13));
    }
}
