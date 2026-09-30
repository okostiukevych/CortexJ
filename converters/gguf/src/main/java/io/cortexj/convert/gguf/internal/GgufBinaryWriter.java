package io.cortexj.convert.gguf.internal;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Little-endian writer for the GGUF container format (v3).
 */
public final class GgufBinaryWriter {

    private final OutputStream output;

    public GgufBinaryWriter(OutputStream output) {
        this.output = output;
    }

    public void writeUInt32(int value) throws IOException {
        write((byte) value);
        write((byte) (value >>> 8));
        write((byte) (value >>> 16));
        write((byte) (value >>> 24));
    }

    public void writeInt64(long value) throws IOException {
        for (int i = 0; i < 8; i++) {
            write((byte) (value >>> (8 * i)));
        }
    }

    public void writeFloat32(float value) throws IOException {
        writeUInt32(Float.floatToIntBits(value));
    }

    public void writeString(String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeInt64(bytes.length);
        output.write(bytes);
    }

    public void writeValue(int type, Object value) throws IOException {
        switch (type) {
            case GgufBinaryReader.Types.UINT8:
                write(((Number) value).intValue() & 0xFF);
                return;
            case GgufBinaryReader.Types.INT8:
                write(((Number) value).byteValue());
                return;
            case GgufBinaryReader.Types.UINT16:
                write((byte) (((Number) value).intValue() & 0xFF));
                write((byte) ((((Number) value).intValue() >>> 8) & 0xFF));
                return;
            case GgufBinaryReader.Types.INT16:
                int short16 = ((Number) value).shortValue();
                write((byte) short16);
                write((byte) (short16 >>> 8));
                return;
            case GgufBinaryReader.Types.UINT32:
                writeUInt32((int) (((Number) value).longValue() & 0xFFFFFFFFL));
                return;
            case GgufBinaryReader.Types.INT32:
                writeUInt32(((Number) value).intValue());
                return;
            case GgufBinaryReader.Types.FLOAT32:
                writeFloat32(((Number) value).floatValue());
                return;
            case GgufBinaryReader.Types.BOOL:
                write(Boolean.TRUE.equals(value) ? (byte) 1 : (byte) 0);
                return;
            case GgufBinaryReader.Types.STRING:
                writeString((String) value);
                return;
            case GgufBinaryReader.Types.ARRAY:
                GgufBinaryReader.ArrayValue array = (GgufBinaryReader.ArrayValue) value;
                writeUInt32(array.elementType);
                writeInt64(array.values.size());
                for (Object element : array.values) {
                    writeValue(array.elementType, element);
                }
                return;
            case GgufBinaryReader.Types.UINT64:
            case GgufBinaryReader.Types.INT64:
                writeInt64(((Number) value).longValue());
                return;
            case GgufBinaryReader.Types.FLOAT64:
                long bits = Double.doubleToLongBits(((Number) value).doubleValue());
                for (int i = 0; i < 8; i++) {
                    write((byte) (bits >>> (8 * i)));
                }
                return;
            default:
                throw new IOException("unsupported GGUF metadata value type: " + type);
        }
    }

    public void write(byte[] bytes) throws IOException {
        output.write(bytes);
    }

    public void write(byte[] bytes, int offset, int length) throws IOException {
        output.write(bytes, offset, length);
    }

    public void flush() throws IOException {
        output.flush();
    }

    private void write(int value) throws IOException {
        output.write(value);
    }
}
