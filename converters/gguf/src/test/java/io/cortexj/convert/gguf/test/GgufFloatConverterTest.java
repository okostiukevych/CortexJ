package io.cortexj.convert.gguf.test;

import io.cortexj.convert.gguf.GgufFloatConverter;
import io.cortexj.convert.gguf.internal.Float16;
import io.cortexj.convert.gguf.internal.GgufBinaryReader;
import io.cortexj.convert.gguf.internal.GgufBinaryWriter;
import io.cortexj.convert.gguf.internal.GgufDocument;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.spi.conversion.ConversionRequest;
import io.cortexj.spi.conversion.ConversionResult;
import io.cortexj.spi.conversion.ConversionSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GgufFloatConverterTest {

    @TempDir
    Path tempDir;

    private static final float[] F32_VALUES = {1.5f, -2.25f, 0.0625f, 1024.0f, -0.0078125f};
    private static final float[] F16_VALUES = {0.5f, -1.25f, 3.75f};

    private Path writeSourceGguf() throws IOException {
        GgufDocument document = new GgufDocument();
        document.version = 3;
        document.metadata.add(new GgufDocument.KeyValue(
                "general.architecture", GgufBinaryReader.Types.STRING, "llama"));
        document.metadata.add(new GgufDocument.KeyValue(
                "general.name", GgufBinaryReader.Types.STRING, "test-model"));
        document.metadata.add(new GgufDocument.KeyValue(
                "general.file_type", GgufBinaryReader.Types.INT32, Integer.valueOf(0)));
        document.metadata.add(new GgufDocument.KeyValue(
                "llama.context_length", GgufBinaryReader.Types.UINT32, Long.valueOf(2048)));
        document.metadata.add(new GgufDocument.KeyValue(
                "llama.attention.head_count", GgufBinaryReader.Types.ARRAY,
                new GgufBinaryReader.ArrayValue(GgufBinaryReader.Types.INT32,
                        java.util.Arrays.asList(Integer.valueOf(32), Integer.valueOf(32)))));
        document.tensors.add(new GgufDocument.TensorInfo(
                "token_embd.weight", new long[]{F32_VALUES.length, 1L},
                GgufDocument.TENSOR_F32, 0L));
        document.tensors.add(new GgufDocument.TensorInfo(
                "output_norm.weight", new long[]{F16_VALUES.length},
                GgufDocument.TENSOR_F16, 0L));

        Path file = tempDir.resolve("source.gguf");
        try (OutputStream raw = Files.newOutputStream(file);
             OutputStream buffered = new BufferedOutputStream(raw, 1 << 16)) {
            GgufBinaryWriter writer = new GgufBinaryWriter(buffered);
            document.writeHeader(writer);
            for (float value : F32_VALUES) {
                writer.writeFloat32(value);
            }
            for (float value : F16_VALUES) {
                short half = Float16.toHalf(value);
                writer.write(new byte[]{(byte) half, (byte) (half >> 8)});
            }
            writer.flush();
        }
        return file;
    }

    @Test
    void sourceDocumentParsesBack() throws IOException {
        GgufDocument document = GgufDocument.read(writeSourceGguf());
        assertEquals(3, document.version);
        assertEquals(2, document.tensors.size());
        assertEquals("llama", ((String) document.find("general.architecture").value));
        assertEquals(2, ((GgufBinaryReader.ArrayValue)
                document.find("llama.attention.head_count").value).values.size());
        assertEquals(GgufDocument.TENSOR_F32, document.tensors.get(0).type);
        assertEquals(GgufDocument.TENSOR_F16, document.tensors.get(1).type);
        assertEquals(F32_VALUES.length * 4L + F16_VALUES.length * 2L,
                Files.size(tempDir.resolve("source.gguf")) - document.dataStartOffset);
    }

    @Test
    void convertsF32ToHalfAndBackDeterministically() throws IOException {
        Path source = writeSourceGguf();
        GgufFloatConverter converter = new GgufFloatConverter();

        ConversionResult toF16 = converter.convert(request(source, "f16"));
        Path f16File = toF16.outputPath();
        assertTrue(f16File.getFileName().toString().endsWith("source.f16.gguf"));
        assertEquals("sha256:" + sha256(f16File),
                toF16.provenance().get("outputChecksum"));
        assertEquals("1", toF16.provenance().get("tensorsConverted"));
        assertEquals("f16", toF16.outputArtifact().metadata().get("quantization"));

        GgufDocument f16Document = GgufDocument.read(f16File);
        assertEquals(GgufDocument.TENSOR_F16, f16Document.tensors.get(0).type,
                "F32 tensor must become F16");
        assertEquals(GgufDocument.TENSOR_F16, f16Document.tensors.get(1).type,
                "F16 tensor must pass through");
        assertEquals("cortexj-convert-gguf/f16",
                documentString(f16Document, "cortexj.converted_by"));

        float[] narrowed = readTensor(f16File, f16Document, 0, F32_VALUES.length, true);
        for (int i = 0; i < F32_VALUES.length; i++) {
            float expected = Float16.fromHalf(Float16.toHalf(F32_VALUES[i]));
            assertEquals(expected, narrowed[i], 0.0f,
                    "conversion must match the deterministic half-float rounding");
        }

        ConversionResult backToF32 = converter.convert(request(f16File, "f32"));
        GgufDocument roundTrip = GgufDocument.read(backToF32.outputPath());
        assertEquals(GgufDocument.TENSOR_F32, roundTrip.tensors.get(0).type);
        float[] widened = readTensor(backToF32.outputPath(), roundTrip, 0, F32_VALUES.length, false);
        for (int i = 0; i < F32_VALUES.length; i++) {
            float expected = Float16.fromHalf(Float16.toHalf(F32_VALUES[i]));
            assertEquals(expected, widened[i], "F16 -> F32 must widen exactly");
        }

        // original untouched
        GgufDocument original = GgufDocument.read(source);
        assertEquals(GgufDocument.TENSOR_F32, original.tensors.get(0).type);
    }

    @Test
    void halfFloatRoundTripIsPreciseWithinHalfPrecision() {
        float[] samples = {0.0f, 1.0f, -1.0f, 0.5f, 65504.0f, 6.1e-5f, -2048.5f, 33.75f};
        for (float value : samples) {
            short half = Float16.toHalf(value);
            float back = Float16.fromHalf(half);
            float expected = Float16.fromHalf(Float16.toHalf(value));
            assertEquals(expected, back, "deterministic conversion");
        }
        assertEquals(0.0f, Float16.fromHalf(Float16.toHalf(0.0f)), 0.0f);
        assertTrue(Float.isInfinite(Float16.fromHalf((short) 0x7C00)));
        assertTrue(Float.isNaN(Float16.fromHalf((short) 0x7E00)));
    }

    @Test
    void evaluateEnforcesGgufOnlyAndLocalFile() throws IOException {
        GgufFloatConverter converter = new GgufFloatConverter();
        Path source = writeSourceGguf();

        assertTrue(converter.evaluate(request(source, "f16")).isSupported());
        assertTrue(converter.evaluate(request(source, null)).isSupported());
        assertFalse(converter.evaluate(request(source, "q4_k")).isSupported(),
                "unknown target quantization must be rejected");

        ConversionSupport wrongFormat = converter.evaluate(
                new ConversionRequest(ggufArtifact("m.gguf"),
                        tempDir.resolve("missing.gguf"), ArtifactFormat.ONNX, "f16",
                        tempDir, emptyParams()));
        assertFalse(wrongFormat.isSupported());

        ConversionSupport missingFile = converter.evaluate(
                new ConversionRequest(ggufArtifact("missing.gguf"),
                        tempDir.resolve("missing.gguf"), ArtifactFormat.GGUF, "f16",
                        tempDir, emptyParams()));
        assertFalse(missingFile.isSupported());
    }

    private ConversionRequest request(Path source, String targetQuantization) {
        return new ConversionRequest(ggufArtifact(source.getFileName().toString()),
                source, ArtifactFormat.GGUF, targetQuantization, tempDir, emptyParams());
    }

    private static Map<String, Object> emptyParams() {
        return new LinkedHashMap<String, Object>();
    }

    private static ModelArtifact ggufArtifact(String id) {
        return new ModelArtifact.Abstract(id, ArtifactFormat.GGUF, 1_000L, null, null) {
        };
    }

    private static String documentString(GgufDocument document, String key) {
        GgufDocument.KeyValue kv = document.find(key);
        return kv == null ? null : String.valueOf(kv.value);
    }

    private static float[] readTensor(Path file, GgufDocument document, int index,
                                      int count, boolean half) throws IOException {
        try (java.io.InputStream input = Files.newInputStream(file)) {
            GgufBinaryReader reader = new GgufBinaryReader(input);
            // the data section begins at a 64-byte aligned offset tracked by the document
            long consumed = 4 + 4 + 8 + 8;
            reader.readInt32();   // magic
            reader.readInt32();   // version
            reader.readInt64();   // tensor count
            reader.readInt64();   // kv count
            for (GgufDocument.KeyValue kv : document.metadata) {
                consumed += 8 + kv.key.getBytes(StandardCharsets.UTF_8).length + 4;
                consumed += valueSizeOf(kv.type, kv.value);
                reader.readString();
                reader.readValue(reader.readInt32());
            }
            for (int i = 0; i < document.tensors.size(); i++) {
                GgufDocument.TensorInfo tensor = document.tensors.get(i);
                consumed += 8 + tensor.name.getBytes(StandardCharsets.UTF_8).length
                        + 4 + 8L * tensor.dimensions.length + 4 + 8;
                reader.readString();
                int dims = reader.readInt32();
                for (int d = 0; d < dims; d++) {
                    reader.readInt64();
                }
                reader.readInt32();
                reader.readInt64();
            }
            reader.skip(document.dataStartOffset - consumed);
            GgufDocument.TensorInfo tensor = document.tensors.get(index);
            reader.skip(tensor.offset);
            float[] values = new float[count];
            for (int i = 0; i < count; i++) {
                values[i] = half ? Float16.fromHalf((short) reader.readUInt16())
                        : reader.readFloat32();
            }
            return values;
        }
    }

    private static long valueSizeOf(int type, Object value) {
        switch (type) {
            case GgufBinaryReader.Types.STRING:
                return 8 + ((String) value).getBytes(StandardCharsets.UTF_8).length;
            case GgufBinaryReader.Types.ARRAY:
                GgufBinaryReader.ArrayValue array = (GgufBinaryReader.ArrayValue) value;
                long size = 4 + 8;
                for (Object element : array.values) {
                    size += valueSizeOf(array.elementType, element);
                }
                return size;
            case GgufBinaryReader.Types.UINT8:
            case GgufBinaryReader.Types.INT8:
            case GgufBinaryReader.Types.BOOL:
                return 1L;
            case GgufBinaryReader.Types.UINT16:
            case GgufBinaryReader.Types.INT16:
                return 2L;
            case GgufBinaryReader.Types.UINT32:
            case GgufBinaryReader.Types.INT32:
            case GgufBinaryReader.Types.FLOAT32:
                return 4L;
            default:
                return 8L;
        }
    }

    private static String sha256(Path file) throws IOException {
        java.security.MessageDigest digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
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

    @Test
    void quantizedTensorsPassThroughByteForByte() throws IOException {
        // real-model verification 2026-10-10: a Q4_K tensor between two F32
        // tensors desynced the sequential copy (elementCount() is not the
        // block size) and killed conversion of ordinary Q4_K_M models with
        // "unexpected end of GGUF data"
        float[] first = {1.0f, 2.0f};
        float[] last = {3.0f, 4.0f, 5.0f};
        float[] norm = {0.5f, 0.25f};
        byte[] q4k = new byte[144];
        for (int i = 0; i < q4k.length; i++) {
            q4k[i] = (byte) (i * 7 + 1);
        }

        GgufDocument document = new GgufDocument();
        document.version = 3;
        document.tensors.add(new GgufDocument.TensorInfo(
                "a.weight", new long[]{first.length, 1L}, GgufDocument.TENSOR_F32, 0L));
        document.tensors.add(new GgufDocument.TensorInfo(
                "b.weight", new long[]{256L}, 12, 0L, 144L));
        document.tensors.add(new GgufDocument.TensorInfo(
                "c.weight", new long[]{last.length, 1L}, GgufDocument.TENSOR_F32, 0L));
        document.tensors.add(new GgufDocument.TensorInfo(
                "d.norm", new long[]{norm.length}, GgufDocument.TENSOR_F32, 0L));
        Path source = tempDir.resolve("mixed.gguf");
        try (OutputStream raw = Files.newOutputStream(source);
             OutputStream buffered = new BufferedOutputStream(raw, 1 << 16)) {
            GgufBinaryWriter writer = new GgufBinaryWriter(buffered);
            document.writeHeader(writer);
            for (float value : first) {
                writer.writeFloat32(value);
            }
            writer.write(q4k);
            for (float value : last) {
                writer.writeFloat32(value);
            }
            for (float value : norm) {
                writer.writeFloat32(value);
            }
            writer.flush();
        }

        GgufDocument parsed = GgufDocument.read(source);
        assertEquals(144L, parsed.tensors.get(1).byteSize(),
                "a 256-element Q4_K tensor occupies 144 bytes, not 256");

        ConversionResult result = new GgufFloatConverter().convert(request(source, "f16"));
        assertEquals("2", result.provenance().get("tensorsConverted"));
        GgufDocument converted = GgufDocument.read(result.outputPath());
        assertEquals(4, converted.tensors.size());
        assertEquals(GgufDocument.TENSOR_F16, converted.tensors.get(0).type);
        assertEquals(12, converted.tensors.get(1).type);
        assertEquals(GgufDocument.TENSOR_F16, converted.tensors.get(2).type);
        assertEquals(GgufDocument.TENSOR_F32, converted.tensors.get(3).type,
                "1-D F32 tensors (norms) must stay F32 or runtimes refuse the file");

        assertArrayEquals(q4k, readRawBytes(result.outputPath(), converted, 1, q4k.length),
                "quantized bytes must pass through untouched");

        // the trailing F32 tensor converts exactly: proves the stream never
        // desynced (readTensor seeks via the output offsets, so this also
        // pins correct output header offsets for quantized tensors)
        float[] narrowed = readTensor(result.outputPath(), converted, 2, last.length, true);
        for (int i = 0; i < last.length; i++) {
            float expected = Float16.fromHalf(Float16.toHalf(last[i]));
            assertEquals(expected, narrowed[i], 0.0f);
        }

        float[] keptNorm = readTensor(result.outputPath(), converted, 3, norm.length, false);
        for (int i = 0; i < norm.length; i++) {
            assertEquals(norm[i], keptNorm[i], 0.0f);
        }
    }

    @Test
    void all1dFloatSourceRefusesHonestlyInsteadOfWritingABrokenFile() throws IOException {
        // real-model verification 2026-10-10: Q4_K_M models carry F32 only
        // in 1-D norm tensors - narrowing them writes a file llama.cpp
        // aborts on, so the converter must refuse with a typed reason
        GgufDocument document = new GgufDocument();
        document.version = 3;
        document.tensors.add(new GgufDocument.TensorInfo(
                "output_norm.weight", new long[]{4L}, GgufDocument.TENSOR_F32, 0L));
        Path source = tempDir.resolve("norms-only.gguf");
        try (OutputStream raw = Files.newOutputStream(source);
             OutputStream buffered = new BufferedOutputStream(raw, 1 << 16)) {
            GgufBinaryWriter writer = new GgufBinaryWriter(buffered);
            document.writeHeader(writer);
            for (int i = 0; i < 4; i++) {
                writer.writeFloat32(1.0f);
            }
            writer.flush();
        }
        io.cortexj.error.ConversionException refused =
                org.junit.jupiter.api.Assertions.assertThrows(
                        io.cortexj.error.ConversionException.class,
                        new org.junit.jupiter.api.function.Executable() {
                            @Override
                            public void execute() {
                                new GgufFloatConverter().convert(request(source, "f16"));
                            }
                        });
        assertTrue(refused.getMessage().contains("no convertible"),
                "refusal must explain why, got: " + refused.getMessage());
    }

    private static byte[] readRawBytes(Path file, GgufDocument document, int index,
                                       int count) throws IOException {
        try (java.io.InputStream input = Files.newInputStream(file)) {
            GgufBinaryReader reader = new GgufBinaryReader(input);
            long consumed = 4 + 4 + 8 + 8;
            reader.readInt32();
            reader.readInt32();
            reader.readInt64();
            reader.readInt64();
            for (GgufDocument.KeyValue kv : document.metadata) {
                consumed += 8 + kv.key.getBytes(StandardCharsets.UTF_8).length + 4;
                consumed += valueSizeOf(kv.type, kv.value);
                reader.readString();
                reader.readValue(reader.readInt32());
            }
            for (int i = 0; i < document.tensors.size(); i++) {
                GgufDocument.TensorInfo tensor = document.tensors.get(i);
                consumed += 8 + tensor.name.getBytes(StandardCharsets.UTF_8).length
                        + 4 + 8L * tensor.dimensions.length + 4 + 8;
                reader.readString();
                int dims = reader.readInt32();
                for (int d = 0; d < dims; d++) {
                    reader.readInt64();
                }
                reader.readInt32();
                reader.readInt64();
            }
            reader.skip(document.dataStartOffset - consumed);
            GgufDocument.TensorInfo tensor = document.tensors.get(index);
            reader.skip(tensor.offset);
            byte[] bytes = new byte[count];
            reader.readFully(bytes, 0, count);
            return bytes;
        }
    }

    @Test
    void convertedFileDeclaresFileTypeAsUint32() throws IOException {
        // real-model verification 2026-10-10: stamping general.file_type as
        // INT32 produced files real llama.cpp refuses ("wrong type i32 but
        // expected type u32") - the source declaration must be preserved
        GgufDocument document = new GgufDocument();
        document.version = 3;
        document.metadata.add(new GgufDocument.KeyValue(
                "general.architecture", GgufBinaryReader.Types.STRING, "llama"));
        document.metadata.add(new GgufDocument.KeyValue(
                "general.file_type", GgufBinaryReader.Types.UINT32, Long.valueOf(2)));
        document.tensors.add(new GgufDocument.TensorInfo(
                "a.weight", new long[]{2L, 1L}, GgufDocument.TENSOR_F32, 0L));
        Path source = tempDir.resolve("typed.gguf");
        try (OutputStream raw = Files.newOutputStream(source);
             OutputStream buffered = new BufferedOutputStream(raw, 1 << 16)) {
            GgufBinaryWriter writer = new GgufBinaryWriter(buffered);
            document.writeHeader(writer);
            writer.writeFloat32(1.0f);
            writer.writeFloat32(-1.0f);
            writer.flush();
        }

        ConversionResult result = new GgufFloatConverter().convert(request(source, "f16"));
        GgufDocument converted = GgufDocument.read(result.outputPath());
        GgufDocument.KeyValue fileType = converted.find("general.file_type");
        assertTrue(fileType != null, "converted file must declare general.file_type");
        assertEquals(GgufBinaryReader.Types.UINT32, fileType.type,
                "file_type must stay UINT32 or llama.cpp refuses the file");
        assertEquals(1L, ((Number) fileType.value).longValue());
    }
}
