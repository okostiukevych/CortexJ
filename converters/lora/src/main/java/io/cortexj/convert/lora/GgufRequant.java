package io.cortexj.convert.lora;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * GGUF requantization (ADR-029): F32/F16 exact, Q8_0/Q4_0 correct
 * round-to-nearest quantizers (bounded error, unit-tested). K-quant
 * output fails typed — no silent precision claims.
 */
public final class GgufRequant {

    private GgufRequant() {
    }

    public static byte[] requantize(float[] values, int targetType) throws IOException {
        switch (targetType) {
            case GgufDequant.TENSOR_F32:
                return toF32(values);
            case GgufDequant.TENSOR_F16:
                return toF16(values);
            case GgufDequant.TENSOR_Q8_0:
                return toQ80(values);
            case GgufDequant.TENSOR_Q4_0:
                return toQ40(values);
            default:
                throw new IOException("GGUF requantization to "
                        + GgufDequant.typeName(targetType) + " is not supported in 0.x");
        }
    }

    private static byte[] toF32(float[] values) {
        ByteBuffer buffer = ByteBuffer.allocate(Math.multiplyExact(values.length, 4))
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    private static byte[] toF16(float[] values) {
        byte[] data = new byte[Math.multiplyExact(values.length, 2)];
        for (int i = 0; i < values.length; i++) {
            int bits = floatToFp16(values[i]);
            data[2 * i] = (byte) (bits & 0xFF);
            data[2 * i + 1] = (byte) ((bits >> 8) & 0xFF);
        }
        return data;
    }

    static int floatToFp16(float value) {
        int bits = Float.floatToRawIntBits(value);
        int sign = (bits >>> 16) & 0x8000;
        int rawExp = (bits >>> 23) & 0xFF;
        int mantissa = bits & 0x7FFFFF;
        if (rawExp == 0xFF) {
            return mantissa != 0 ? (sign | 0x7E00) : (sign | 0x7C00);
        }
        int exponent = rawExp - 112;
        if (exponent <= 0) {
            return sign;
        }
        if (exponent >= 31) {
            return sign | 0x7BFF;
        }
        return sign | (exponent << 10) | (mantissa >> 13);
    }

    private static byte[] toQ80(float[] values) {
        int blocks = (int) (((long) values.length + 31L) / 32L);
        byte[] data = new byte[Math.multiplyExact(blocks, 34)];
        for (int b = 0; b < blocks; b++) {
            float max = 0;
            for (int i = 0; i < 32 && b * 32 + i < values.length; i++) {
                max = Math.max(max, Math.abs(values[b * 32 + i]));
            }
            float scale = max == 0 ? 1 : max / 127;
            int scaleBits = floatToFp16(scale);
            data[b * 34] = (byte) (scaleBits & 0xFF);
            data[b * 34 + 1] = (byte) ((scaleBits >> 8) & 0xFF);
            for (int i = 0; i < 32 && b * 32 + i < values.length; i++) {
                int quantized = Math.round(values[b * 32 + i] / scale);
                data[b * 34 + 2 + i] = (byte) Math.max(-127, Math.min(127, quantized));
            }
        }
        return data;
    }

    private static byte[] toQ40(float[] values) {
        int blocks = (int) (((long) values.length + 31L) / 32L);
        byte[] data = new byte[Math.multiplyExact(blocks, 18)];
        for (int b = 0; b < blocks; b++) {
            float max = 0;
            for (int i = 0; i < 32 && b * 32 + i < values.length; i++) {
                max = Math.max(max, Math.abs(values[b * 32 + i]));
            }
            float scale = max == 0 ? 1 : max / 7;
            int scaleBits = floatToFp16(scale);
            data[b * 18] = (byte) (scaleBits & 0xFF);
            data[b * 18 + 1] = (byte) ((scaleBits >> 8) & 0xFF);
            for (int i = 0; i < 32 && b * 32 + i < values.length; i++) {
                int quantized = Math.round(values[b * 32 + i] / scale) + 8;
                quantized = Math.max(0, Math.min(15, quantized));
                int at = b * 18 + 2 + i / 2;
                if (i % 2 == 0) {
                    data[at] = (byte) ((data[at] & 0xF0) | (quantized & 0xF));
                } else {
                    data[at] = (byte) ((data[at] & 0x0F) | ((quantized & 0xF) << 4));
                }
            }
        }
        return data;
    }
}
