package io.cortexj.convert.safetensors.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cortexj.convert.safetensors.SafetensorsToGgufConverter;
import io.cortexj.error.ConversionException;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.spi.conversion.ConversionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafetensorsPartCleanupTest {

    @TempDir
    Path tempDir;

    /** Writes a spec-shaped safetensors file (one F32 tensor of 12 bytes), data truncated by 4 bytes. */
    private Path writeTruncatedSafetensors() throws IOException {
        ObjectNode header = new ObjectMapper().createObjectNode();
        ObjectNode f32 = header.putObject("weights");
        f32.put("dtype", "F32");
        ArrayNode f32Shape = f32.putArray("shape");
        f32Shape.add(3);
        ArrayNode f32Offsets = f32.putArray("data_offsets");
        f32Offsets.add(0);
        f32Offsets.add(12L);

        byte[] json = header.toString().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ByteBuffer size = ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        size.putLong(json.length).flip();
        buffer.write(size.array());
        buffer.write(json);
        ByteBuffer data = ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        data.putFloat(1.5f);
        data.putFloat(-2.25f);
        data.putFloat(4.0f);
        buffer.write(data.array());

        Path file = tempDir.resolve("truncated-model.safetensors");
        byte[] full = buffer.toByteArray();
        // truncate the data section: the header still declares 12 bytes, so the
        // converter fails mid-write after the temp file is created
        Files.write(file, Arrays.copyOf(full, full.length - 4));
        return file;
    }

    private ConversionRequest request(Path source) {
        ModelArtifact artifact = new ModelArtifact.Abstract(
                source.getFileName().toString(), ArtifactFormat.SAFETENSORS, 1_000L, null, null) {
        };
        return new ConversionRequest(artifact, source, ArtifactFormat.GGUF, null,
                tempDir, new LinkedHashMap<String, Object>());
    }

    private static boolean anyPartLeft(Path directory) throws IOException {
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            return files.anyMatch(p -> p.getFileName().toString().endsWith(".part"));
        }
    }

    @Test
    void failedMidWriteConversionLeavesNoPartFile() throws IOException {
        Path source = writeTruncatedSafetensors();
        long truncatedSize = Files.size(source);

        SafetensorsToGgufConverter converter = new SafetensorsToGgufConverter();
        assertThrows(ConversionException.class, () -> converter.convert(request(source)),
                "a mid-tensor EOF must surface as a typed ConversionException");

        assertTrue(!anyPartLeft(tempDir),
                "a failed conversion must not strand a model-sized .part file");
        assertTrue(Files.size(source) == truncatedSize,
                "the source artifact must be untouched by the failed conversion");
    }
}
