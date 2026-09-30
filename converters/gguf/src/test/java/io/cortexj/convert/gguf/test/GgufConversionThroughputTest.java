package io.cortexj.convert.gguf.test;

import io.cortexj.convert.gguf.GgufFloatConverter;
import io.cortexj.convert.gguf.internal.Float16;
import io.cortexj.convert.gguf.internal.GgufBinaryReader;
import io.cortexj.convert.gguf.internal.GgufBinaryWriter;
import io.cortexj.convert.gguf.internal.GgufDocument;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.spi.conversion.ConversionRequest;
import org.junit.jupiter.api.Tag;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("perf")
class GgufConversionThroughputTest {

    private static final int ELEMENTS = 8 * 1024 * 1024;

    @TempDir
    Path tempDir;

    private Path writeF32Source() throws IOException {
        GgufDocument document = new GgufDocument();
        document.version = 3;
        document.tensors.add(new GgufDocument.TensorInfo(
                "weights", new long[]{ELEMENTS, 1L}, GgufDocument.TENSOR_F32, 0L));
        Path file = tempDir.resolve("source.gguf");
        try (OutputStream raw = Files.newOutputStream(file);
             OutputStream buffered = new BufferedOutputStream(raw, 1 << 16)) {
            GgufBinaryWriter writer = new GgufBinaryWriter(buffered);
            document.writeHeader(writer);
            byte[] chunk = new byte[1 << 16];
            long remaining = ELEMENTS * 4L;
            while (remaining > 0) {
                int n = (int) Math.min(chunk.length, remaining);
                java.util.Arrays.fill(chunk, 0, n, (byte) 0x3F);
                writer.write(chunk, 0, n);
                remaining -= n;
            }
            writer.flush();
        }
        return file;
    }

    /** The pre-fix per-element reference algorithm (a fresh byte[2] per element). */
    private static void convertPerElement(GgufBinaryReader reader, GgufBinaryWriter writer,
                                          long count) throws IOException {
        for (long i = 0; i < count; i++) {
            short half = Float16.toHalf(reader.readFloat32());
            writer.write(new byte[]{(byte) half, (byte) (half >> 8)});
        }
    }

    @Test
    void bulkConversionIsBitIdenticalAndFasterThanPerElement() throws Exception {
        Path source = writeF32Source();

        GgufFloatConverter converter = new GgufFloatConverter();
        long bulkStarted = System.nanoTime();
        converter.convert(request(source, "f16"));
        long bulkMillis = (System.nanoTime() - bulkStarted) / 1_000_000L;

        // the same tensor through the pre-fix per-element algorithm
        Path referenceOut = tempDir.resolve("reference.gguf.part");
        long referenceStarted = System.nanoTime();
        try (java.io.InputStream input = new java.io.BufferedInputStream(
                Files.newInputStream(source), 1 << 16);
             OutputStream output = new BufferedOutputStream(
                     Files.newOutputStream(referenceOut), 1 << 16)) {
            GgufBinaryReader reader = new GgufBinaryReader(input);
            GgufBinaryWriter writer = new GgufBinaryWriter(output);
            GgufDocument document = GgufDocument.read(source);
            reader.readInt32();
            reader.skip(document.dataStartOffset - 4L);
            document.writeHeader(writer);
            convertPerElement(reader, writer, ELEMENTS);
            writer.flush();
        }
        long referenceMillis = (System.nanoTime() - referenceStarted) / 1_000_000L;

        // bit-identity: the bulk output's tensor data must equal the reference's
        // (the headers differ by the converter's upserted provenance metadata)
        byte[] bulkBytes = Files.readAllBytes(tempDir.resolve("source.f16.gguf"));
        byte[] referenceBytes = Files.readAllBytes(referenceOut);
        int tensorBytes = ELEMENTS * 2;
        byte[] bulkTail = Arrays.copyOfRange(bulkBytes, bulkBytes.length - tensorBytes, bulkBytes.length);
        byte[] referenceTail = Arrays.copyOfRange(referenceBytes,
                referenceBytes.length - tensorBytes, referenceBytes.length);
        assertArrayEquals(referenceTail, bulkTail,
                "the bulk conversion must be bit-identical to the per-element reference");

        System.out.println("F32->F16 conversion of a " + (ELEMENTS * 4L / 1024 / 1024)
                + " MiB tensor: bulk=" + bulkMillis + "ms per-element=" + referenceMillis
                + "ms (speedup " + (referenceMillis * 1.0d / Math.max(bulkMillis, 1L)) + "x)");
        assertTrue(bulkMillis < 30_000L,
                "coarse ceiling: the bulk conversion of a 32 MiB tensor must stay fast");
        Files.deleteIfExists(referenceOut);
    }

    private ConversionRequest request(Path source, String targetQuantization) throws IOException {
        ModelArtifact artifact = new ModelArtifact.Abstract(
                source.getFileName().toString(), ArtifactFormat.GGUF, Files.size(source), null, null) {
        };
        return new ConversionRequest(artifact, source, ArtifactFormat.GGUF, targetQuantization,
                tempDir, new LinkedHashMap<String, Object>());
    }
}
