package io.cortexj.convert.gguf.internal;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Little-endian reader for the GGUF container format (v2/v3).
 */
public final class GgufBinaryReader {

    private static final long MAX_STRING_LENGTH = 1024L * 1024L;
    private static final long MAX_BYTE_BLOCK_LENGTH = 64L * 1024L * 1024L;
    private static final long MAX_ARRAY_ELEMENTS = 1024L * 1024L;
    private static final int MAX_METADATA_DEPTH = 8;

    private final InputStream input;

    public GgufBinaryReader(InputStream input) {
        this.input = input;
    }

    public int readUInt16() throws IOException {
        int b1 = read();
        int b2 = read();
        return (b2 << 8) | b1;
    }

    public int readInt32() throws IOException {
        int b1 = read();
        int b2 = read();
        int b3 = read();
        int b4 = read();
        return (b4 << 24) | (b3 << 16) | (b2 << 8) | b1;
    }

    public long readInt64() throws IOException {
        long result = 0L;
        for (int i = 0; i < 8; i++) {
            result |= (read() & 0xFFL) << (8 * i);
        }
        return result;
    }

    public float readFloat32() throws IOException {
        return Float.intBitsToFloat(readInt32());
    }

    public double readFloat64() throws IOException {
        return Double.longBitsToDouble(readInt64());
    }

    public String readString() throws IOException {
        long length = readInt64();
        if (length < 0 || length > MAX_STRING_LENGTH) {
            throw new GgufFormatException("unreasonable GGUF string length: " + length);
        }
        byte[] bytes = new byte[(int) length];
        readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public byte[] readBytes(long count) throws IOException {
        if (count < 0 || count > MAX_BYTE_BLOCK_LENGTH) {
            throw new GgufFormatException("unreasonable GGUF byte count: " + count);
        }
        byte[] bytes = new byte[(int) count];
        readFully(bytes);
        return bytes;
    }

    public void skip(long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped <= 0) {
                if (read() < 0) {
                    throw new EOFException("unexpected end of GGUF data");
                }
                remaining--;
                continue;
            }
            remaining -= skipped;
        }
    }

    private void readFully(byte[] target) throws IOException {
        readFully(target, 0, target.length);
    }

    public void readFully(byte[] target, int offset, int length) throws IOException {
        int position = offset;
        int end = offset + length;
        while (position < end) {
            int read = input.read(target, position, end - position);
            if (read < 0) {
                throw new EOFException("unexpected end of GGUF data");
            }
            position += read;
        }
    }

    private int read() throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new EOFException("unexpected end of GGUF data");
        }
        return value;
    }

    public static final class Types {

        public static final int UINT8 = 0;
        public static final int INT8 = 1;
        public static final int UINT16 = 2;
        public static final int INT16 = 3;
        public static final int UINT32 = 4;
        public static final int INT32 = 5;
        public static final int FLOAT32 = 6;
        public static final int BOOL = 7;
        public static final int STRING = 8;
        public static final int ARRAY = 9;
        public static final int UINT64 = 10;
        public static final int INT64 = 11;
        public static final int FLOAT64 = 12;

        private Types() {
        }
    }

    public static final class ArrayValue {

        public final int elementType;
        public final List<Object> values;

        public ArrayValue(int elementType, List<Object> values) {
            this.elementType = elementType;
            this.values = values;
        }
    }

    public Object readValue(int type) throws IOException {
        return readValue(type, 0);
    }

    public Object readValue(int type, int depth) throws IOException {
        switch (type) {
            case Types.UINT8:
                return Integer.valueOf(read() & 0xFF);
            case Types.INT8:
                return Byte.valueOf((byte) read());
            case Types.UINT16:
                return Integer.valueOf(readUInt16());
            case Types.INT16:
                return Short.valueOf((short) readUInt16());
            case Types.UINT32:
                return Long.valueOf(readInt32() & 0xFFFFFFFFL);
            case Types.INT32:
                return Integer.valueOf(readInt32());
            case Types.FLOAT32:
                return Float.valueOf(readFloat32());
            case Types.BOOL:
                return Boolean.valueOf(read() != 0);
            case Types.STRING:
                return readString();
            case Types.ARRAY:
                if (depth >= MAX_METADATA_DEPTH) {
                    throw new GgufFormatException(
                            "GGUF metadata nested deeper than " + MAX_METADATA_DEPTH + " levels");
                }
                int elementType = readInt32();
                if (!isScalarElementType(elementType)) {
                    throw new GgufFormatException(
                            "GGUF metadata arrays must contain scalar elements, got element type " + elementType);
                }
                long count = readInt64();
                if (count < 0 || count > MAX_ARRAY_ELEMENTS) {
                    throw new GgufFormatException("unreasonable GGUF array length: " + count);
                }
                List<Object> values = new ArrayList<Object>();
                for (long i = 0; i < count; i++) {
                    values.add(readValue(elementType, depth + 1));
                }
                return new ArrayValue(elementType, values);
            case Types.UINT64:
                return Long.valueOf(readInt64());
            case Types.INT64:
                return Long.valueOf(readInt64());
            case Types.FLOAT64:
                return Double.valueOf(readFloat64());
            default:
                throw new GgufFormatException("unsupported GGUF metadata value type: " + type);
        }
    }

    private static boolean isScalarElementType(int elementType) {
        if (elementType >= Types.UINT8 && elementType <= Types.STRING) {
            return true;
        }
        return elementType >= Types.UINT64 && elementType <= Types.FLOAT64;
    }
}
