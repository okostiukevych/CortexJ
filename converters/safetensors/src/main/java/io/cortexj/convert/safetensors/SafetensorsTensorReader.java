package io.cortexj.convert.safetensors;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tensor-level safetensors reading for training bases: decodes
 * F32/F16/BF16 tensors to float[] keyed by tensor name. The container
 * parsing reuses {@link SafetensorsToGgufConverter.SafetensorsFile};
 * unsupported dtypes fail typed naming the dtype (ADR-023: the
 * safetensors module owns the container format).
 */
public final class SafetensorsTensorReader {

    public static Map<String, float[]> readAllFloats(Path file) throws IOException {
        SafetensorsToGgufConverter.SafetensorsFile parsed =
                SafetensorsToGgufConverter.SafetensorsFile.read(file);
        Map<String, float[]> out = new LinkedHashMap<String, float[]>();
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            SafetensorsToGgufConverter.SafetensorsFile.skipHeader(in);
            long position = 0;
            for (SafetensorsToGgufConverter.SafetensorsFile.TensorInfo tensor : parsed.tensors()) {
                long skip = tensor.dataOffsets[0] - position;
                if (skip > 0) {
                    long skipped = 0;
                    while (skipped < skip) {
                        long chunk = in.skip(skip - skipped);
                        if (chunk <= 0) {
                            throw new IOException("safetensors stream ended before tensor "
                                    + tensor.name);
                        }
                        skipped += chunk;
                    }
                }
                byte[] raw = new byte[(int) tensor.byteSize];
                int read = 0;
                while (read < raw.length) {
                    int chunk = in.read(raw, read, raw.length - read);
                    if (chunk < 0) {
                        throw new IOException("safetensors stream ended inside tensor "
                                + tensor.name);
                    }
                    read += chunk;
                }
                position = tensor.dataOffsets[1];
                out.put(tensor.name, decode(tensor.dtype, raw));
            }
        }
        return out;
    }

    static float[] decode(String dtype, byte[] raw) throws IOException {
        if ("F32".equals(dtype)) {
            ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            float[] values = new float[raw.length / 4];
            for (int i = 0; i < values.length; i++) {
                values[i] = buffer.getFloat();
            }
            return values;
        }
        if ("F16".equals(dtype) || "BF16".equals(dtype)) {
            ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            float[] values = new float[raw.length / 2];
            for (int i = 0; i < values.length; i++) {
                short half = buffer.getShort();
                values[i] = "F16".equals(dtype)
                        ? halfToFloat(half)
                        : Float.intBitsToFloat((half & 0xFFFF) << 16);
            }
            return values;
        }
        throw new IOException("unsupported safetensors dtype for training bases: " + dtype
                + " (supported: F32, F16, BF16)");
    }

    static float halfToFloat(short half) {
        int sign = (half >> 15) & 0x1;
        int exponent = (half >> 10) & 0x1F;
        int mantissa = half & 0x3FF;
        float value;
        if (exponent == 0) {
            value = (float) (mantissa * Math.pow(2, -24));
        } else if (exponent == 31) {
            value = mantissa == 0 ? Float.POSITIVE_INFINITY : Float.NaN;
        } else {
            value = (float) Math.pow(2, exponent - 15) * (1 + mantissa / 1024.0f);
        }
        return sign == 0 ? value : -value;
    }

    private SafetensorsTensorReader() {
    }
}
