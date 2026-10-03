package io.cortexj.model.artifact;

import io.cortexj.model.ArtifactFormat;
import io.cortexj.spi.artifact.ArtifactInspection;
import io.cortexj.spi.artifact.ArtifactInspector;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class GgufArtifactInspector implements ArtifactInspector {

    private static final int GGUF_MAGIC = 0x46554747;

    private static final long MAX_STRING_LENGTH = 1024L * 1024L;
    private static final long MAX_ARRAY_ELEMENTS = 1024L * 1024L;
    private static final int MAX_METADATA_DEPTH = 8;

    private static final int T_UINT8 = 0;
    private static final int T_INT8 = 1;
    private static final int T_UINT16 = 2;
    private static final int T_INT16 = 3;
    private static final int T_UINT32 = 4;
    private static final int T_INT32 = 5;
    private static final int T_FLOAT32 = 6;
    private static final int T_BOOL = 7;
    private static final int T_STRING = 8;
    private static final int T_ARRAY = 10;
    private static final int T_UINT64 = 11;
    private static final int T_INT64 = 12;
    private static final int T_FLOAT64 = 13;

    @Override
    public boolean supports(ArtifactFormat format) {
        return format == ArtifactFormat.GGUF;
    }

    @Override
    public ArtifactInspection inspect(Path artifact) {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(artifact), 64 * 1024)) {
            return inspectStream(input);
        } catch (IOException e) {
            throw new MalformedArtifactException("Cannot read GGUF artifact " + artifact + ": " + e.getMessage(), e);
        }
    }

    public ArtifactInspection inspectStream(InputStream input) throws IOException {
        GgufReader reader = new GgufReader(input);
        if (reader.readInt() != GGUF_MAGIC) {
            throw new IllegalArgumentException("Not a GGUF file: bad magic");
        }
        int version = reader.readInt();
        if (version < 1 || version > 4) {
            throw new IllegalArgumentException("Unsupported GGUF version: " + version);
        }
        long tensorCount = reader.readLong();
        long metadataCount = reader.readLong();

        ArtifactInspection.Builder builder = ArtifactInspection.builder(ArtifactFormat.GGUF);
        builder.metadata("ggufVersion", String.valueOf(version));
        builder.metadata("tensorCount", String.valueOf(tensorCount));

        String architecture = null;
        String name = null;
        String quantization = null;
        long contextLength = 0;
        String sizeLabel = null;

        for (long i = 0; i < metadataCount; i++) {
            String key = reader.readString();
            int valueType = reader.readInt();
            Object value = reader.readValue(valueType);
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value);
            if (key.equals("general.architecture")) {
                architecture = text;
            } else if (key.equals("general.name")) {
                name = text;
            } else if (key.equals("general.file_type") || key.equals("general.quantization_version")) {
                Number number = asNumber(value);
                if (number != null) {
                    quantization = ggufFileType(number.intValue());
                } else {
                    quantization = text;
                }
            } else if (key.endsWith(".context_length")) {
                Number number = asNumber(value);
                if (number != null) {
                    contextLength = number.longValue();
                }
            } else if (key.equals("general.size_label")) {
                sizeLabel = text;
            }
        }
        if (architecture != null) {
            builder.architecture(architecture);
        }
        if (name != null) {
            builder.metadata("name", name);
        }
        if (quantization != null) {
            builder.quantization(quantization);
        }
        if (contextLength > 0) {
            builder.contextLength((int) Math.min(contextLength, Integer.MAX_VALUE));
        }
        if (sizeLabel != null) {
            builder.metadata("sizeLabel", sizeLabel);
        }
        return builder.build();
    }

    private static Number asNumber(Object value) {
        return value instanceof Number ? (Number) value : null;
    }

    static String ggufFileType(int fileType) {
        switch (fileType) {
            case 0: return "f32";
            case 1: return "f16";
            case 2: return "q4_0";
            case 3: return "q4_1";
            case 7: return "q8_0";
            case 8: return "q5_0";
            case 9: return "q5_1";
            case 10: return "q2_k";
            case 11: return "q3_k_s";
            case 12: return "q3_k_m";
            case 13: return "q3_k_l";
            case 14: return "q4_k_s";
            case 15: return "q4_k_m";
            case 16: return "q5_k_s";
            case 17: return "q5_k_m";
            case 18: return "q6_k";
            case 24: return "bf16";
            default: return "type-" + fileType;
        }
    }

    static final class GgufReader {

        private final InputStream input;
        private final byte[] buffer = new byte[8];

        GgufReader(InputStream input) {
            this.input = input;
        }

        int readInt() throws IOException {
            readFully(4);
            return ByteBuffer.wrap(buffer, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        }

        long readLong() throws IOException {
            readFully(8);
            return ByteBuffer.wrap(buffer, 0, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
        }

        int readUnsignedByte() throws IOException {
            int value = input.read();
            if (value < 0) {
                throw new EOFException("Truncated GGUF header");
            }
            return value;
        }

        String readString() throws IOException {
            long length = readLong();
            if (length < 0 || length > MAX_STRING_LENGTH) {
                throw new MalformedArtifactException("Unreasonable GGUF string length: " + length);
            }
            byte[] bytes = new byte[(int) length];
            int offset = 0;
            while (offset < bytes.length) {
                int read = input.read(bytes, offset, bytes.length - offset);
                if (read < 0) {
                    throw new EOFException("Truncated GGUF string");
                }
                offset += read;
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }

        Object readValue(int valueType) throws IOException {
            return readValue(valueType, 0);
        }

        Object readValue(int valueType, int depth) throws IOException {
            switch (valueType) {
                case T_UINT8: return Integer.valueOf(readUnsignedByte());
                case T_INT8: return Integer.valueOf((byte) readUnsignedByte());
                case T_UINT16: return Integer.valueOf(readUnsignedByte() | (readUnsignedByte() << 8));
                case T_INT16: {
                    int low = readUnsignedByte();
                    int high = readUnsignedByte();
                    return Integer.valueOf((short) (low | (high << 8)));
                }
                case T_UINT32: return Long.valueOf(readInt() & 0xFFFFFFFFL);
                case T_INT32: return Integer.valueOf(readInt());
                case T_FLOAT32: return Float.valueOf(Float.intBitsToFloat(readInt()));
                case T_BOOL: return Boolean.valueOf(readUnsignedByte() != 0);
                case T_STRING: return readString();
                case T_ARRAY: {
                    if (depth >= MAX_METADATA_DEPTH) {
                        throw new MalformedArtifactException(
                                "GGUF metadata nested deeper than " + MAX_METADATA_DEPTH + " levels");
                    }
                    int elementType = readerArrayType();
                    if (!isScalarElementType(elementType)) {
                        throw new MalformedArtifactException(
                                "GGUF metadata arrays must contain scalar elements, got element type " + elementType);
                    }
                    long length = readLong();
                    if (length < 0 || length > MAX_ARRAY_ELEMENTS) {
                        throw new MalformedArtifactException("Unreasonable array length: " + length);
                    }
                    List<Object> values = new ArrayList<Object>();
                    for (long i = 0; i < length; i++) {
                        values.add(readValue(elementType, depth + 1));
                    }
                    return values;
                }
                case T_UINT64: return Long.valueOf(readLong());
                case T_INT64: return Long.valueOf(readLong());
                case T_FLOAT64: return Double.valueOf(Double.longBitsToDouble(readLong()));
                default:
                    throw new EOFException("Unsupported GGUF metadata value type: " + valueType);
            }
        }

        private static boolean isScalarElementType(int elementType) {
            if (elementType >= T_UINT8 && elementType <= T_STRING) {
                return true;
            }
            return elementType >= T_UINT64 && elementType <= T_FLOAT64;
        }

        private int readerArrayType() throws IOException {
            return readInt();
        }

        private void readFully(int count) throws IOException {
            int offset = 0;
            while (offset < count) {
                int read = input.read(buffer, offset, count - offset);
                if (read < 0) {
                    throw new EOFException("Truncated GGUF header");
                }
                offset += read;
            }
        }
    }
}
