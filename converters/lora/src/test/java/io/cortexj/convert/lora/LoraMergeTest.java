package io.cortexj.convert.lora;

import io.cortexj.convert.gguf.internal.GgufBinaryWriter;
import io.cortexj.convert.gguf.internal.GgufDocument;
import io.cortexj.train.peft.LoRAAdapter;
import io.cortexj.train.peft.LoRAConfig;
import io.cortexj.train.peft.LoRASafetensors;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline merge on a synthetic GGUF: exact float math, manifest
 * contents, typed failures.
 */
class LoraMergeTest {

    private static Path baseModel(float[] weights, String name) throws Exception {
        Path file = Files.createTempFile("base", ".gguf");
        GgufDocument document = new GgufDocument();
        document.version = 3;
        document.tensors.add(new GgufDocument.TensorInfo(name,
                new long[]{2, 2}, GgufDequant.TENSOR_F32, 0));
        document.tensors.add(new GgufDocument.TensorInfo("other.weight",
                new long[]{2}, GgufDequant.TENSOR_F32, 0));
        try (OutputStream stream = Files.newOutputStream(file)) {
            GgufBinaryWriter writer = new GgufBinaryWriter(stream);
            document.writeHeader(writer);
            ByteBuffer buffer = ByteBuffer.allocate(20 * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (float value : weights) {
                buffer.putFloat(value);
            }
            for (float value : new float[]{9.0f, 9.0f}) {
                buffer.putFloat(value);
            }
            writer.write(buffer.array());
        }
        return file;
    }

    private static Path adapterFile() throws Exception {
        LoRAConfig config = new LoRAConfig(2, 4, 0.0, Arrays.asList("q_proj"));
        float[][] a = {{0.5f, -0.25f}, {1.0f, 0.0f}};
        float[][] b = {{1.0f, 0.0f}, {0.25f, 0.5f}};
        LoRAAdapter adapter = new LoRAAdapter("q_proj", 2, 2, config, a, b);
        Path file = Files.createTempFile("adapter", ".safetensors");
        LoRASafetensors.write(adapter, file, Map.of());
        return file;
    }

    @Test
    void mergeAppliesAdapterExactly() throws Exception {
        Path base = baseModel(new float[]{1.0f, 2.0f, 3.0f, 4.0f}, "blk.0.attn_q.weight");
        Path adapter = adapterFile();
        Path output = Files.createTempDirectory("merged").resolve("merged.gguf");
        try {
            LoraMerge.Result result = LoraMerge.merge(new LoraMerge.Request(
                    base,
                    Collections.singletonList(
                            new LoraMerge.Mapping("blk.0.attn_q.weight", adapter)),
                    LoraMerge.QuantPolicy.F32,
                    output));
            assertEquals(Collections.singletonList("blk.0.attn_q.weight"),
                    result.mergedTensors());
            assertTrue(Files.isRegularFile(result.manifest()));
            assertTrue(result.manifestEntries().containsKey("converter"));
            assertTrue(result.manifestEntries().containsKey(
                    "adapter.blk.0.attn_q.weight"));

            // read back: merged = base + 2*B*A, untouched tensor identical
            GgufDocument merged = GgufDocument.read(output);
            assertEquals(2, merged.tensors.size());
            float[] produced = readTensor(output, merged, "blk.0.attn_q.weight");
            // B*A = [[0.5,-0.25],[0.625,-0.0625]]; merged = base + 2*that
            assertEquals(1.0f + 2 * 0.5f, produced[0], 1e-5);
            assertEquals(2.0f + 2 * (-0.25f), produced[1], 1e-5);
            assertEquals(3.0f + 2 * 0.625f, produced[2], 1e-5);
            assertEquals(4.0f + 2 * (-0.0625f), produced[3], 1e-5);
            float[] untouched = readTensor(output, merged, "other.weight");
            assertEquals(9.0f, untouched[0], 1e-9);
            assertEquals(9.0f, untouched[1], 1e-9);
        } finally {
            Files.deleteIfExists(base);
            Files.deleteIfExists(adapter);
            Files.deleteIfExists(output);
            Files.deleteIfExists(Path.of(output + ".manifest.properties"));
        }
    }

    @Test
    void shapeMismatchFailsTyped() throws Exception {
        Path base = baseModel(new float[]{1.0f, 2.0f, 3.0f, 4.0f}, "blk.0.attn_q.weight");
        // adapter for a 3-output module cannot merge into 4 elements
        LoRAConfig config = new LoRAConfig(2, 4, 0.0, Arrays.asList("q_proj"));
        LoRAAdapter wrong = new LoRAAdapter("q_proj", 2, 3, config,
                new float[][]{{0.5f, -0.25f}, {1.0f, 0.0f}},
                new float[][]{{1.0f, 0.0f}, {0.25f, 0.5f}, {0.0f, 2.0f}});
        Path adapter = Files.createTempFile("adapter", ".safetensors");
        Path output = Files.createTempDirectory("merged").resolve("merged.gguf");
        try {
            LoRASafetensors.write(wrong, adapter, Map.of());
            java.io.IOException failure = assertThrows(java.io.IOException.class,
                    () -> LoraMerge.merge(new LoraMerge.Request(base,
                            Collections.singletonList(
                                    new LoraMerge.Mapping("blk.0.attn_q.weight", adapter)),
                            LoraMerge.QuantPolicy.F32, output)));
            assertTrue(failure.getMessage().contains("4"), failure.getMessage());
        } finally {
            Files.deleteIfExists(base);
            Files.deleteIfExists(adapter);
        }
    }

    @Test
    void duplicateMappingFailsTyped() throws Exception {
        Path base = baseModel(new float[]{1.0f, 2.0f, 3.0f, 4.0f}, "blk.0.attn_q.weight");
        Path adapter = adapterFile();
        Path output = Files.createTempDirectory("merged").resolve("merged.gguf");
        try {
            assertThrows(java.io.IOException.class, () -> LoraMerge.merge(
                    new LoraMerge.Request(base, Arrays.asList(
                            new LoraMerge.Mapping("blk.0.attn_q.weight", adapter),
                            new LoraMerge.Mapping("blk.0.attn_q.weight", adapter)),
                            LoraMerge.QuantPolicy.F32, output)));
        } finally {
            Files.deleteIfExists(base);
            Files.deleteIfExists(adapter);
        }
    }

    private static float[] readTensor(Path file, GgufDocument document, String name)
            throws Exception {
        GgufDocument.TensorInfo info = null;
        for (GgufDocument.TensorInfo tensor : document.tensors) {
            if (tensor.name.equals(name)) {
                info = tensor;
            }
        }
        byte[] all = Files.readAllBytes(file);
        int at = (int) (document.dataStartOffset + info.offset);
        ByteBuffer buffer = ByteBuffer.wrap(all, at, (int) info.elementCount() * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        float[] values = new float[(int) info.elementCount()];
        for (int i = 0; i < values.length; i++) {
            values[i] = buffer.getFloat();
        }
        return values;
    }
}
