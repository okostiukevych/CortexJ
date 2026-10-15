package io.cortexj.convert.safetensors.test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Minimal safetensors container writer for tests: F32/F16/BF16 1-D/2-D tensors. */
public final class SafetensorsFixture {

    public static final class Tensor {
        public final String dtype;
        public final long[] shape;
        public final byte[] data;

        public Tensor(String dtype, long[] shape, byte[] data) {
            this.dtype = dtype;
            this.shape = shape;
            this.data = data;
        }
    }

    public static byte[] f32(float... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : values) {
            buffer.putFloat(v);
        }
        return buffer.array();
    }

    public static byte[] f16(float... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : values) {
            buffer.putShort(floatToHalf(v));
        }
        return buffer.array();
    }

    public static byte[] bf16(float... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : values) {
            buffer.putShort((short) (Float.floatToIntBits(v) >>> 16));
        }
        return buffer.array();
    }

    public static void write(Path file, Map<String, Tensor> tensors) throws IOException {
        long offset = 0;
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Tensor> e : tensors.entrySet()) {
            long byteSize = e.getValue().data.length;
            if (!first) {
                json.append(",");
            }
            first = false;
            json.append("\"").append(e.getKey()).append("\":{\"dtype\":\"")
                    .append(e.getValue().dtype)
                    .append("\",\"shape\":[").append(shapeJson(e.getValue().shape))
                    .append("],\"data_offsets\":[").append(offset).append(",")
                    .append(offset + byteSize).append("]}");
            offset += byteSize;
        }
        json.append("}");
        byte[] jsonBytes = json.toString().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        header.putLong(jsonBytes.length);
        out.write(header.array());
        out.write(jsonBytes);
        for (Tensor t : tensors.values()) {
            out.write(t.data);
        }
        Files.write(file, out.toByteArray());
    }

    private static String shapeJson(long[] shape) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < shape.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(shape[i]);
        }
        return sb.toString();
    }

    private static short floatToHalf(float value) {
        int bits = Float.floatToIntBits(value);
        int sign = (bits >>> 16) & 0x8000;
        int exponent = ((bits >>> 23) & 0xFF) - 127 + 15;
        int mantissa = (bits >>> 13) & 0x3FF;
        if (exponent <= 0) {
            return (short) sign;
        }
        if (exponent >= 31) {
            return (short) (sign | 0x7C00);
        }
        return (short) (sign | (exponent << 10) | mantissa);
    }

    private SafetensorsFixture() {
    }
}
