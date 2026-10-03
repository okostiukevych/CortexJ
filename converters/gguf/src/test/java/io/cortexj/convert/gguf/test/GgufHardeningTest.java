package io.cortexj.convert.gguf.test;

import io.cortexj.convert.gguf.internal.GgufBinaryReader;
import io.cortexj.convert.gguf.internal.GgufDocument;
import io.cortexj.convert.gguf.internal.GgufFormatException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GgufHardeningTest {

    private static void writeInt(ByteArrayOutputStream out, int value) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value);
        out.write(buffer.array());
    }

    private static void writeLong(ByteArrayOutputStream out, long value) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value);
        out.write(buffer.array());
    }

    private static void writeString(ByteArrayOutputStream out, String value) throws IOException {
        writeLong(out, value.getBytes(StandardCharsets.UTF_8).length);
        out.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] ggufWithTensorDimensions(int dimensions) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        writeInt(bytes, GgufDocument.GGUF_MAGIC);
        writeInt(bytes, 3);
        writeLong(bytes, 1L);
        writeLong(bytes, 0L);
        writeString(bytes, "test.weight");
        writeInt(bytes, dimensions);
        return bytes.toByteArray();
    }

    private static byte[] ggufWithNestedArray() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        writeInt(bytes, GgufDocument.GGUF_MAGIC);
        writeInt(bytes, 3);
        writeLong(bytes, 0L);
        writeLong(bytes, 1L);
        writeString(bytes, "test.array");
        writeInt(bytes, GgufBinaryReader.Types.ARRAY);
        writeInt(bytes, GgufBinaryReader.Types.ARRAY);
        writeLong(bytes, 2L);
        writeLong(bytes, 2L);
        return bytes.toByteArray();
    }

    @Test
    void binaryReaderRejectsHugeString() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        writeLong(bytes, 2L * 1024L * 1024L);
        GgufBinaryReader reader = new GgufBinaryReader(new ByteArrayInputStream(bytes.toByteArray()));
        GgufFormatException e = assertThrows(GgufFormatException.class, reader::readString);
        assertTrue(e.getMessage().contains("string length"));
    }

    @Test
    void binaryReaderRejectsOversizedReadBytes() {
        GgufBinaryReader reader = new GgufBinaryReader(new ByteArrayInputStream(new byte[0]));
        assertThrows(GgufFormatException.class, () -> reader.readBytes(64L * 1024L * 1024L + 1L));
        GgufBinaryReader negative = new GgufBinaryReader(new ByteArrayInputStream(new byte[0]));
        assertThrows(GgufFormatException.class, () -> negative.readBytes(-1L));
    }

    @Test
    void documentRejectsHugeTensorDimensions() throws IOException {
        GgufFormatException huge = assertThrows(GgufFormatException.class,
                () -> GgufDocument.read(new ByteArrayInputStream(ggufWithTensorDimensions(Integer.MAX_VALUE))));
        assertTrue(huge.getMessage().contains("dimension"));
        GgufFormatException negative = assertThrows(GgufFormatException.class,
                () -> GgufDocument.read(new ByteArrayInputStream(ggufWithTensorDimensions(-1))));
        assertTrue(negative.getMessage().contains("dimension"));
    }

    @Test
    void readValueRejectsNestedArrays() throws IOException {
        GgufBinaryReader reader = new GgufBinaryReader(new ByteArrayInputStream(ggufWithNestedArray()));
        assertThrows(GgufFormatException.class, () -> reader.readValue(GgufBinaryReader.Types.ARRAY));
    }

    @Test
    void readValueRejectsHugeArrayCount() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        writeInt(bytes, GgufBinaryReader.Types.UINT8);
        writeLong(bytes, 1024L * 1024L + 1L);
        GgufBinaryReader reader = new GgufBinaryReader(new ByteArrayInputStream(bytes.toByteArray()));
        assertThrows(GgufFormatException.class, () -> reader.readValue(GgufBinaryReader.Types.ARRAY));
    }
}
