package io.cortexj.convert.gguf.test;

import io.cortexj.convert.gguf.GgufFloatConverter;
import io.cortexj.convert.gguf.internal.GgufBinaryWriter;
import io.cortexj.convert.gguf.internal.GgufDocument;
import io.cortexj.error.ConversionException;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.spi.conversion.ConversionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GgufPartCleanupTest {

    @TempDir
    Path tempDir;

    private Path writeTruncatedSourceGguf() throws IOException {
        GgufDocument document = new GgufDocument();
        document.version = 3;
        document.tensors.add(new GgufDocument.TensorInfo(
                "weights", new long[]{4L, 1L}, GgufDocument.TENSOR_F32, 0L));
        Path file = tempDir.resolve("truncated.gguf");
        try (OutputStream raw = Files.newOutputStream(file);
             OutputStream buffered = new BufferedOutputStream(raw, 1 << 16)) {
            GgufBinaryWriter writer = new GgufBinaryWriter(buffered);
            document.writeHeader(writer);
            for (int i = 0; i < 4; i++) {
                writer.writeFloat32(1.0f);
            }
            writer.flush();
        }
        // truncate the data section: the header still declares 16 bytes of F32
        // data, so the converter fails mid-write after the temp file is created
        byte[] full = Files.readAllBytes(file);
        Files.write(file, Arrays.copyOf(full, full.length - 8));
        return file;
    }

    private ConversionRequest request(Path source) {
        ModelArtifact artifact = new ModelArtifact.Abstract(
                source.getFileName().toString(), ArtifactFormat.GGUF, 1_000L, null, null) {
        };
        return new ConversionRequest(artifact, source, ArtifactFormat.GGUF, "f16",
                tempDir, new LinkedHashMap<String, Object>());
    }

    private static boolean anyPartLeft(Path directory) throws IOException {
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            return files.anyMatch(p -> p.getFileName().toString().endsWith(".part"));
        }
    }

    @Test
    void failedMidWriteConversionLeavesNoPartFile() throws IOException {
        Path source = writeTruncatedSourceGguf();
        long truncatedSize = Files.size(source);

        GgufFloatConverter converter = new GgufFloatConverter();
        assertThrows(ConversionException.class, () -> converter.convert(request(source)),
                "a mid-tensor EOF must surface as a typed ConversionException");

        assertTrue(!anyPartLeft(tempDir),
                "a failed conversion must not strand a model-sized .part file");
        assertTrue(Files.size(source) == truncatedSize,
                "the source artifact must be untouched by the failed conversion");
    }
}
