package io.cortexj.convert.lora;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * GGUF block dequantization (ADR-029): exact layouts for the
 * unambiguous formats. K-quants and unknown types fail typed —
 * guessed layouts are worse than no support.
 */
public final class GgufDequant {

    public static final int TENSOR_F32 = 0;
    public static final int TENSOR_F16 = 1;
    public static final int TENSOR_Q4_0 = 2;
    public static final int TENSOR_Q4_1 = 3;
    public static final int TENSOR_Q5_0 = 6;
    public static final int TENSOR_Q5_1 = 7;
    public static final int TENSOR_Q8_0 = 8;
    public static final int TENSOR_BF16 = 30;

    private GgufDequant() {
    }

    public static boolean supported(int tensorType) {
        switch (tensorType) {
            case TENSOR_F32:
            case TENSOR_F16:
            case TENSOR_BF16:
            case TENSOR_Q4_0:
            case TENSOR_Q4_1:
            case TENSOR_Q5_0:
            case TENSOR_Q5_1:
            case TENSOR_Q8_0:
                return true;
            default:
                return false;
        }
    }

    public static String typeName(int tensorType) {
        switch (tensorType) {
            case TENSOR_F32:
                return "F32";
            case TENSOR_F16:
                return "F16";
            case TENSOR_Q4_0:
                return "Q4_0";
            case TENSOR_Q4_1:
                return "Q4_1";
            case TENSOR_Q5_0:
                return "Q5_0";
            case TENSOR_Q5_1:
                return "Q5_1";
            case TENSOR_Q8_0:
                return "Q8_0";
            case TENSOR_BF16:
                return "BF16";
            default:
                return "type-" + tensorType;
        }
    }

    /**
     * Dequantizes one tensor's raw bytes into F32 values.
     */
    public static float[] dequantize(int tensorType, byte[] data, long elements)
            throws IOException {
        switch (tensorType) {
            case TENSOR_F32:
                return readF32(data, elements);
            case TENSOR_F16:
                return readF16(data, elements);
            case TENSOR_BF16:
                return readBF16(data, elements);
            case TENSOR_Q8_0:
                return dequantQ80(data, elements);
            case TENSOR_Q4_0:
                return dequantQ40(data, elements, false);
            case TENSOR_Q4_1:
                return dequantQ40(data, elements, true);
            case TENSOR_Q5_0:
                return dequantQ50(data, elements, false);
            case TENSOR_Q5_1:
                return dequantQ50(data, elements, true);
            default:
                throw new IOException("GGUF dequantization is not supported for "
                        + typeName(tensorType) + " in 0.x");
        }
    }

    private static float[] readF32(byte[] data, long elements) throws IOException {
        requireElements(elements);
        require(data, Math.multiplyExact(elements, 4L), "F32");
        float[] values = new float[(int) elements];
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < values.length; i++) {
            values[i] = buffer.getFloat();
        }
        return values;
    }

    private static float[] readF16(byte[] data, long elements) throws IOException {
        requireElements(elements);
        require(data, Math.multiplyExact(elements, 2L), "F16");
        float[] values = new float[(int) elements];
        for (int i = 0; i < values.length; i++) {
            int bits = (data[2 * i] & 0xFF) | ((data[2 * i + 1] & 0xFF) << 8);
            values[i] = fp16ToFloat(bits);
        }
        return values;
    }

    private static float[] readBF16(byte[] data, long elements) throws IOException {
        requireElements(elements);
        require(data, Math.multiplyExact(elements, 2L), "BF16");
        float[] values = new float[(int) elements];
        for (int i = 0; i < values.length; i++) {
            int bits = ((data[2 * i] & 0xFF) << 16) | ((data[2 * i + 1] & 0xFF) << 24);
            values[i] = Float.intBitsToFloat(bits);
        }
        return values;
    }

    private static void requireElements(long elements) throws IOException {
        if (elements < 0 || elements > Integer.MAX_VALUE) {
            throw new IOException("unreasonable GGUF tensor element count: " + elements);
        }
    }

    private static void require(byte[] data, long needed, String what) throws IOException {
        if (data.length < needed) {
            throw new IOException("truncated " + what + " tensor data");
        }
    }

    static float fp16ToFloat(int bits) {
        int sign = (bits >> 15) & 1;
        int exponent = (bits >> 10) & 0x1F;
        int mantissa = bits & 0x3FF;
        int floatBits;
        if (exponent == 0) {
            if (mantissa == 0) {
                floatBits = sign << 31;
            } else {
                // subnormal: renormalize
                exponent = 1;
                while ((mantissa & 0x400) == 0) {
                    mantissa <<= 1;
                    exponent--;
                }
                mantissa &= 0x3FF;
                floatBits = (sign << 31) | ((exponent + 112) << 23) | (mantissa << 13);
            }
        } else if (exponent == 31) {
            floatBits = (sign << 31) | (0xFF << 23) | (mantissa << 13);
        } else {
            floatBits = (sign << 31) | ((exponent + 112) << 23) | (mantissa << 13);
        }
        return Float.intBitsToFloat(floatBits);
    }

    private static float[] dequantQ80(byte[] data, long elements) throws IOException {
        requireElements(elements);
        int blocks = (int) ((elements + 31) / 32);
        require(data, (long) blocks * 34, "Q8_0");
        float[] values = new float[(int) elements];
        for (int b = 0; b < blocks; b++) {
            int base = b * 34;
            float scale = fp16ToFloat((data[base] & 0xFF) | ((data[base + 1] & 0xFF) << 8));
            for (int i = 0; i < 32 && b * 32 + i < elements; i++) {
                values[b * 32 + i] = data[base + 2 + i] * scale;
            }
        }
        return values;
    }

    private static float[] dequantQ40(byte[] data, long elements, boolean withMin)
            throws IOException {
        requireElements(elements);
        int blockBytes = withMin ? 20 : 18;
        int blocks = (int) ((elements + 31) / 32);
        require(data, (long) blocks * blockBytes, withMin ? "Q4_1" : "Q4_0");
        float[] values = new float[(int) elements];
        for (int b = 0; b < blocks; b++) {
            int base = b * blockBytes;
            float scale = fp16ToFloat((data[base] & 0xFF) | ((data[base + 1] & 0xFF) << 8));
            float min = withMin
                    ? fp16ToFloat((data[base + 2] & 0xFF) | ((data[base + 3] & 0xFF) << 8))
                    : 0;
            int quants = withMin ? base + 4 : base + 2;
            for (int i = 0; i < 32 && b * 32 + i < elements; i++) {
                int nibble = (data[quants + i / 2] >> (4 * (i % 2))) & 0xF;
                values[b * 32 + i] = withMin ? nibble * scale + min : (nibble - 8) * scale;
            }
        }
        return values;
    }

    private static float[] dequantQ50(byte[] data, long elements, boolean withMin)
            throws IOException {
        requireElements(elements);
        int blockBytes = withMin ? 24 : 22;
        int blocks = (int) ((elements + 31) / 32);
        require(data, (long) blocks * blockBytes, withMin ? "Q5_1" : "Q5_0");
        float[] values = new float[(int) elements];
        for (int b = 0; b < blocks; b++) {
            int base = b * blockBytes;
            float scale = fp16ToFloat((data[base] & 0xFF) | ((data[base + 1] & 0xFF) << 8));
            float min = 0;
            int cursor = base + 2;
            if (withMin) {
                min = fp16ToFloat((data[cursor] & 0xFF) | ((data[cursor + 1] & 0xFF) << 8));
                cursor += 2;
            }
            int highOffset = cursor;
            int lowOffset = cursor + 4;
            for (int i = 0; i < 32 && b * 32 + i < elements; i++) {
                int high = (data[highOffset + i / 8] >> (i % 8)) & 1;
                int nibble = (data[lowOffset + i / 2] >> (4 * (i % 2))) & 0xF;
                int quantized = (high << 4) | nibble;
                values[b * 32 + i] = withMin
                        ? quantized * scale + min
                        : (quantized - 16) * scale;
            }
        }
        return values;
    }
}
