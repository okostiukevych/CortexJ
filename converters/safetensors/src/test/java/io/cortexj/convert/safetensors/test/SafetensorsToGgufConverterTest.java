package io.cortexj.convert.safetensors.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cortexj.convert.gguf.internal.GgufDocument;
import io.cortexj.convert.safetensors.SafetensorsToGgufConverter;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.spi.conversion.ConversionRequest;
import io.cortexj.spi.conversion.ConversionResult;
import io.cortexj.spi.conversion.ConversionSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafetensorsToGgufConverterTest {

    @TempDir
    Path tempDir;

    private static final float[] F32_VALUES = {1.5f, -2.25f, 4.0f};
    private static final short[] I16_VALUES = {7, -3};

    /** Writes a minimal, spec-shaped safetensors file with one F32 and one I16 tensor. */
    private Path writeSafetensors(String fileName) throws IOException {
        ObjectNode header = new ObjectMapper().createObjectNode();
        ObjectNode f32 = header.putObject("weights");
        f32.put("dtype", "F32");
        ArrayNode f32Shape = f32.putArray("shape");
        f32Shape.add(F32_VALUES.length);
        ArrayNode f32Offsets = f32.putArray("data_offsets");
        f32Offsets.add(0);
        f32Offsets.add(F32_VALUES.length * 4L);
        ObjectNode i16 = header.putObject("head_count");
        i16.put("dtype", "I16");
        ArrayNode i16Shape = i16.putArray("shape");
        i16Shape.add(I16_VALUES.length);
        ArrayNode i16Offsets = i16.putArray("data_offsets");
        i16Offsets.add(F32_VALUES.length * 4L);
        i16Offsets.add(F32_VALUES.length * 4L + I16_VALUES.length * 2L);

        byte[] json = header.toString().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ByteBuffer size = ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        size.putLong(json.length).flip();
        buffer.write(size.array());
        buffer.write(json);
        ByteBuffer data = ByteBuffer.allocate(F32_VALUES.length * 4 + I16_VALUES.length * 2)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (float value : F32_VALUES) {
            data.putFloat(value);
        }
        for (short value : I16_VALUES) {
            data.putShort(value);
        }
        buffer.write(data.array());

        Path file = tempDir.resolve(fileName);
        Files.write(file, buffer.toByteArray());
        return file;
    }

    @Test
    void convertsToValidGgufWithConfigMetadata() throws Exception {
        Path source = writeSafetensors("test-model.safetensors");
        Files.write(tempDir.resolve("config.json"),
                ("{\"model_type\":\"llama\",\"max_position_embeddings\":4096,"
                        + "\"hidden_size\":2048,\"num_hidden_layers\":16,"
                        + "\"num_attention_heads\":16,\"torch_dtype\":\"float16\"}")
                        .getBytes(StandardCharsets.UTF_8));

        SafetensorsToGgufConverter converter = new SafetensorsToGgufConverter();
        ConversionResult result = converter.convert(request(source));
        Path output = result.outputPath();
        assertTrue(output.getFileName().toString().equals("test-model.gguf"));

        GgufDocument document = GgufDocument.read(output);
        assertEquals(2, document.tensors.size());
        assertEquals("llama", document.find("general.architecture").value);
        assertEquals(Long.valueOf(4096L),
                ((Number) document.find("llama.context_length").value).longValue() == 4096
                        ? Long.valueOf(4096L) : Long.valueOf(-1L));
        assertEquals(GgufDocument.TENSOR_F32, document.tensors.get(0).type);
        assertEquals("weights", document.tensors.get(0).name);
        assertEquals(GgufDocument.TENSOR_F32, document.tensors.get(0).type);
        assertEquals("head_count", document.tensors.get(1).name);
        assertEquals(17, document.tensors.get(1).type); // GGUF I16
        assertEquals("sha256:" + sha256(output),
                result.provenance().get("outputChecksum"));
        assertEquals("true", result.provenance().get("configDerived"));

        // tensor data must be byte-identical to the source payload
        byte[] sourcePayload = payloadAfterHeader(source,
                8 + headerJsonLength(source));
        byte[] outputPayload = payloadAfterHeader(output, (int) document.dataStartOffset);
        assertArrayEquals(sourcePayload, outputPayload);
    }

    @Test
    void worksWithoutAdjacentConfig() throws Exception {
        Path source = writeSafetensors("bare.safetensors");
        SafetensorsToGgufConverter converter = new SafetensorsToGgufConverter();
        ConversionResult result = converter.convert(request(source));
        GgufDocument document = GgufDocument.read(result.outputPath());
        assertEquals("bare", document.find("general.name").value);
        assertEquals("false", result.provenance().get("configDerived"));
        assertTrue(document.find("general.architecture") == null);
    }

    @Test
    void evaluateEnforcesDirectionsAndLocalFile() throws Exception {
        SafetensorsToGgufConverter converter = new SafetensorsToGgufConverter();
        Path source = writeSafetensors("ev.safetensors");

        assertTrue(converter.evaluate(request(source)).isSupported());
        assertFalse(converter.evaluate(new ConversionRequest(
                ggufArtifact("m.gguf", ArtifactFormat.GGUF), source,
                ArtifactFormat.GGUF, null, tempDir, params())).isSupported(),
                "GGUF source must be rejected");
        ConversionSupport wrongTarget = converter.evaluate(new ConversionRequest(
                ggufArtifact("m.safetensors", ArtifactFormat.SAFETENSORS), source,
                ArtifactFormat.ONNX, null, tempDir, params()));
        assertFalse(wrongTarget.isSupported());
    }

    private ConversionRequest request(Path source) {
        return new ConversionRequest(
                ggufArtifact(source.getFileName().toString(), ArtifactFormat.SAFETENSORS),
                source, ArtifactFormat.GGUF, null, tempDir, params());
    }

    private static Map<String, Object> params() {
        return new LinkedHashMap<String, Object>();
    }

    private static ModelArtifact ggufArtifact(String id, ArtifactFormat format) {
        return new ModelArtifact.Abstract(id, format, 1_000L, null, null) {
        };
    }

    private static long headerJsonLength(Path file) throws IOException {
        byte[] sizeBytes = new byte[8];
        try (java.io.InputStream input = Files.newInputStream(file)) {
            readFully(input, sizeBytes);
        }
        long value = 0L;
        for (int i = 0; i < 8; i++) {
            value |= (sizeBytes[i] & 0xFFL) << (8 * i);
        }
        return value;
    }

    private static byte[] payloadAfterHeader(Path file, long skip) throws IOException {
        try (java.io.InputStream input = Files.newInputStream(file)) {
            long remaining = skip;
            while (remaining > 0) {
                long skipped = input.skip(remaining);
                if (skipped <= 0) {
                    if (input.read() < 0) {
                        throw new IOException("truncated");
                    }
                    skipped = 1;
                }
                remaining -= skipped;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = input.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
    }

    private static void readFully(java.io.InputStream input, byte[] target) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            int read = input.read(target, offset, target.length - offset);
            if (read < 0) {
                throw new IOException("truncated");
            }
            offset += read;
        }
    }

    private static String sha256(Path file) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
