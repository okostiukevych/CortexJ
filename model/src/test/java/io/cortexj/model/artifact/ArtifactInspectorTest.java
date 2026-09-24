package io.cortexj.model.artifact;

import io.cortexj.model.ArtifactFormat;
import io.cortexj.spi.artifact.ArtifactInspection;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactInspectorTest {

    private static final int GGUF_MAGIC = 0x46554747;

    private static byte[] ggufBytes() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        writeInt(bytes, GGUF_MAGIC);
        writeInt(bytes, 3);
        writeLong(bytes, 1L);
        writeLong(bytes, 4L);
        writeString(bytes, "general.architecture");
        writeInt(bytes, 8);
        writeString(bytes, "llama");
        writeString(bytes, "general.name");
        writeInt(bytes, 8);
        writeString(bytes, "test-model");
        writeString(bytes, "general.file_type");
        writeInt(bytes, 5);
        writeInt(bytes, 18);
        writeString(bytes, "llama.context_length");
        writeInt(bytes, 5);
        writeInt(bytes, 4096);
        return bytes.toByteArray();
    }

    private static void writeInt(ByteArrayOutputStream out, int value) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value);
        out.write(buffer.array());
    }

    private static void writeLong(ByteArrayOutputStream out, long value) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value);
        out.write(buffer.array());
    }

    private static void writeString(ByteArrayOutputStream out, String value) throws IOException {
        writeLong(out, value.length());
        out.write(value.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void ggufInspectorParsesHeader() throws IOException {
        GgufArtifactInspector inspector = new GgufArtifactInspector();
        ArtifactInspection inspection = inspector.inspectStream(new ByteArrayInputStream(ggufBytes()));
        assertEquals("llama", inspection.architecture());
        assertEquals(4096, inspection.contextLength());
        assertEquals("q6_k", inspection.quantization());
        assertEquals("test-model", inspection.metadata().get("name"));
    }

    @Test
    void ggufFileTypeMapping() {
        assertEquals("q6_k", GgufArtifactInspector.ggufFileType(18));
        assertEquals("q4_k_m", GgufArtifactInspector.ggufFileType(15));
        assertEquals("f16", GgufArtifactInspector.ggufFileType(1));
    }

    @Test
    void ggufInspectorRejectsBadMagic() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        writeInt(bytes, 0x12345678);
        GgufArtifactInspector inspector = new GgufArtifactInspector();
        try {
            inspector.inspectStream(new ByteArrayInputStream(bytes.toByteArray()));
            org.junit.jupiter.api.Assertions.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("magic"));
        }
    }

    @Test
    void safetensorsInspectorParsesHeader() throws IOException {
        String header = "{\"__metadata__\":{\"architecture\":\"bert\",\"quantization\":\"fp16\"},"
                + "\"tensor.weight\":{\"dtype\":\"F16\",\"shape\":[2,3],\"data_offsets\":[0,12]}}";
        byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ByteBuffer length = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(headerBytes.length);
        bytes.write(length.array());
        bytes.write(headerBytes);
        SafetensorsInspector inspector = new SafetensorsInspector();
        ArtifactInspection inspection = inspector.inspectStream(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals("bert", inspection.architecture());
        assertEquals("fp16", inspection.quantization());
        assertEquals(6L, inspection.parameterCount());
    }

    @Test
    void safetensorsInspectorRejectsGarbage() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ByteBuffer length = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(Integer.MAX_VALUE * 2L);
        bytes.write(length.array());
        SafetensorsInspector inspector = new SafetensorsInspector();
        try {
            inspector.inspectStream(new ByteArrayInputStream(bytes.toByteArray()));
            org.junit.jupiter.api.Assertions.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("header"));
        }
    }

    @Test
    void onnxInspectorToleratesProbeLimit() throws IOException {
        InputStream endless = new InputStream() {
            @Override
            public int read() {
                return 0;
            }
        };
        OnnxLightInspector inspector = new OnnxLightInspector();
        ArtifactInspection inspection = inspector.inspectStream(endless, 2048L);
        assertNotNull(inspection);
        assertEquals(ArtifactFormat.ONNX, inspection.format());
    }

    @Test
    void registryFindsInspectorByFormat() throws IOException {
        ArtifactInspectorRegistry registry = new ArtifactInspectorRegistry();
        assertNotNull(registry.forFormat(ArtifactFormat.GGUF));
        assertNotNull(registry.forFormat(ArtifactFormat.SAFETENSORS));
        assertNotNull(registry.forFormat(ArtifactFormat.ONNX));
        assertNull(registry.forFormat(ArtifactFormat.CONFIG));

        Path temp = Files.createTempFile("test-model", ".gguf");
        Files.write(temp, ggufBytes());
        ArtifactInspection inspection = registry.inspectOrNull(ArtifactFormat.GGUF, temp);
        assertNotNull(inspection);
        assertEquals("llama", inspection.architecture());
        Files.deleteIfExists(temp);
    }
}
