package io.cortexj.train.peft;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Safetensors container (ADR-022): 8-byte little-endian header length,
 * a UTF-8 JSON header mapping tensor names to dtype/shape/offsets, then
 * raw F32 tensor data. Standard adapter naming: {@code adapter.A} /
 * {@code adapter.B}; module name and alpha ride the canonical
 * {@code __metadata__} object. Pure Java, no Python.
 */
public final class LoRASafetensors {

    public static final String TENSOR_A = "adapter.A";
    public static final String TENSOR_B = "adapter.B";

    private LoRASafetensors() {
    }

    public static void write(LoRAAdapter adapter, Path file, java.util.Map<String, String> metadata)
            throws IOException {
        float[][] a = adapter.matrixA();
        float[][] b = adapter.matrixB();
        long aBytes = (long) a.length * (a.length > 0 ? a[0].length : 0) * 4;
        long bBytes = (long) b.length * (b.length > 0 ? b[0].length : 0) * 4;
        StringBuilder header = new StringBuilder();
        header.append('{');
        header.append('"').append(TENSOR_A).append("\":{\"dtype\":\"F32\",\"shape\":[")
                .append(a.length).append(',').append(a.length > 0 ? a[0].length : 0)
                .append("],\"data_offsets\":[0,").append(aBytes).append("]}");
        header.append(',');
        header.append('"').append(TENSOR_B).append("\":{\"dtype\":\"F32\",\"shape\":[")
                .append(b.length).append(',').append(b.length > 0 ? b[0].length : 0)
                .append("],\"data_offsets\":[").append(aBytes).append(',')
                .append(aBytes + bBytes).append("]}");
        header.append(",\"__metadata__\":{");
        header.append("\"targetModules\":").append(quote(adapter.moduleName()));
        header.append(",\"alpha\":").append(quote(String.valueOf(adapter.config().alpha())));
        if (metadata != null) {
            for (java.util.Map.Entry<String, String> entry : metadata.entrySet()) {
                header.append(',').append(quote("__metadata__." + entry.getKey()))
                        .append(':').append(quote(entry.getValue()));
            }
        }
        header.append('}');
        header.append('}');
        // pad the header to 8-byte alignment with spaces (safetensors convention)
        byte[] headerBytes = header.toString().getBytes(StandardCharsets.UTF_8);
        int padding = (8 - (headerBytes.length % 8)) % 8;
        byte[] padded = new byte[headerBytes.length + padding];
        System.arraycopy(headerBytes, 0, padded, 0, headerBytes.length);
        for (int i = headerBytes.length; i < padded.length; i++) {
            padded[i] = ' ';
        }

        try (OutputStream output = Files.newOutputStream(file)) {
            byte[] lengthBytes = new byte[8];
            long length = padded.length;
            for (int i = 0; i < 8; i++) {
                lengthBytes[i] = (byte) ((length >>> (8 * i)) & 0xFF);
            }
            output.write(lengthBytes);
            output.write(padded);
            writeTensor(output, a);
            writeTensor(output, b);
        }
    }

    public static LoRAAdapter read(Path file) throws IOException {
        byte[] all = Files.readAllBytes(file);
        if (all.length < 8) {
            throw new IOException("safetensors file is too short: " + file);
        }
        long headerLength = 0;
        for (int i = 0; i < 8; i++) {
            headerLength |= (long) (all[i] & 0xFF) << (8 * i);
        }
        if (headerLength < 2 || headerLength > all.length - 8) {
            throw new IOException("safetensors header length is invalid: " + headerLength);
        }
        String header = new String(all, 8, (int) headerLength, StandardCharsets.UTF_8);
        long dataStart = 8 + headerLength;
        long aOffset = offsetStart(header, TENSOR_A);
        long bOffset = offsetStart(header, TENSOR_B);
        long[] aShape = shape(header, TENSOR_A);
        long[] bShape = shape(header, TENSOR_B);
        if (aShape == null || bShape == null || aOffset < 0 || bOffset < 0) {
            throw new IOException("safetensors header does not declare the adapter tensors");
        }
        float[][] a = readTensor(all, dataStart + aOffset, (int) aShape[0], (int) aShape[1]);
        float[][] b = readTensor(all, dataStart + bOffset, (int) bShape[0], (int) bShape[1]);
        String moduleName = metadataValue(header, "targetModules", "adapter");
        int rank = (int) aShape[0];
        int inputSize = (int) aShape[1];
        int outputSize = (int) bShape[0];
        int alpha = Integer.parseInt(metadataValue(header, "alpha", String.valueOf(rank)));
        LoRAConfig config = new LoRAConfig(rank, alpha, 0.0,
                java.util.Collections.singletonList(moduleName));
        return new LoRAAdapter(moduleName, inputSize, outputSize, config, a, b);
    }

    private static void writeTensor(OutputStream output, float[][] matrix) throws IOException {
        for (float[] row : matrix) {
            for (float value : row) {
                int bits = Float.floatToIntBits(value);
                output.write(bits & 0xFF);
                output.write((bits >>> 8) & 0xFF);
                output.write((bits >>> 16) & 0xFF);
                output.write((bits >>> 24) & 0xFF);
            }
        }
    }

    private static float[][] readTensor(byte[] all, long offset, int rows, int columns) {
        float[][] matrix = new float[rows][columns];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                long position = offset + ((long) r * columns + c) * 4;
                if (position + 4 > all.length) {
                    throw new UncheckedIOException(new IOException(
                            "safetensors data offsets exceed the file size"));
                }
                int bits = (all[(int) position] & 0xFF)
                        | ((all[(int) position + 1] & 0xFF) << 8)
                        | ((all[(int) position + 2] & 0xFF) << 16)
                        | ((all[(int) position + 3] & 0xFF) << 24);
                matrix[r][c] = Float.intBitsToFloat(bits);
            }
        }
        return matrix;
    }

    static long offsetStart(String header, String tensor) {
        String needle = "\"" + tensor + "\"";
        int at = header.indexOf(needle);
        if (at < 0) {
            return -1;
        }
        int offsets = header.indexOf("data_offsets", at);
        if (offsets < 0) {
            return -1;
        }
        int open = header.indexOf('[', offsets);
        int comma = header.indexOf(',', open);
        return Long.parseLong(header.substring(open + 1, comma).trim());
    }

    static long[] shape(String header, String tensor) {
        String needle = "\"" + tensor + "\"";
        int at = header.indexOf(needle);
        if (at < 0) {
            return null;
        }
        int shapeKey = header.indexOf("shape", at);
        int open = header.indexOf('[', shapeKey);
        int close = header.indexOf(']', open);
        String[] parts = header.substring(open + 1, close).split(",");
        long[] shape = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            shape[i] = Long.parseLong(parts[i].trim());
        }
        return shape;
    }

    static String metadataValue(String header, String key, String fallback) {
        int metadata = header.indexOf("\"__metadata__\"");
        if (metadata < 0) {
            return fallback;
        }
        int close = header.indexOf('}', metadata);
        String section = header.substring(metadata, close < 0 ? header.length() : close);
        int keyAt = section.indexOf("\"" + key + "\"");
        if (keyAt < 0) {
            return fallback;
        }
        int colon = section.indexOf(':', keyAt);
        int open = section.indexOf('"', colon);
        int end = section.indexOf('"', open + 1);
        return section.substring(open + 1, end);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
