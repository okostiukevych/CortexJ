package io.cortexj.convert.lora;

import io.cortexj.convert.gguf.internal.GgufBinaryWriter;
import io.cortexj.convert.gguf.internal.GgufDocument;
import io.cortexj.train.peft.LoRAAdapter;
import io.cortexj.train.peft.LoRAConfig;
import io.cortexj.train.peft.LoRASafetensors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoraAdapterGgufTest {

    @TempDir
    Path temp;

    /** rank=2, in=4, out=3 with distinctive values so byte layout is provable. */
    private LoRAAdapter adapter(int alpha) {
        LoRAConfig config = new LoRAConfig(2, alpha, 0.0,
                Collections.singletonList("q_proj"));
        float[][] a = {{1f, 2f, 3f, 4f}, {5f, 6f, 7f, 8f}};      // rank x in
        float[][] b = {{9f, 10f}, {11f, 12f}, {13f, 14f}};       // out x rank
        return new LoRAAdapter("q_proj", 4, 3, config, a, b);
    }

    /** Minimal base GGUF with one 2-D F32 tensor (innermost-first dims). */
    private Path baseGguf(String arch, long[] dims) throws Exception {
        return baseGguf(arch, "blk.0.attn_q.weight", dims);
    }

    private Path baseGguf(String arch, String tensorName, long[] dims) throws Exception {
        GgufDocument doc = new GgufDocument();
        doc.version = 3;
        doc.upsert("general.architecture", 8, arch);
        float[] values = new float[(int) (dims[0] * dims[1])];
        byte[] data = f32(values);
        doc.tensors.add(new GgufDocument.TensorInfo(tensorName,
                dims.clone(), 0, 0, data.length));
        Path file = temp.resolve("base-" + tensorName.replace('.', '-') + ".gguf");
        try (java.io.OutputStream out = Files.newOutputStream(file)) {
            GgufBinaryWriter writer = new GgufBinaryWriter(out);
            doc.writeHeader(writer);
            writer.write(data);
        }
        return file;
    }

    private static byte[] f32(float... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    @Test
    void exportsLlamaCppContractMetadataAndTensorPairs() throws Exception {
        Path safetensors = temp.resolve("adapter.safetensors");
        LoRASafetensors.write(adapter(16), safetensors, Collections.emptyMap());
        Path base = baseGguf("qwen3", new long[]{4, 3});
        Path output = temp.resolve("adapter.gguf");

        LoraAdapterGguf.export(new LoraAdapterGguf.Request(base,
                Collections.singletonList(
                        new LoraAdapterGguf.Entry(safetensors, "blk.0.attn_q.weight")),
                output));

        GgufDocument doc = GgufDocument.read(output);
        assertEquals("adapter", stringValue(doc, "general.type"));
        assertEquals("qwen3", stringValue(doc, "general.architecture"));
        assertEquals("lora", stringValue(doc, "adapter.type"));
        assertEquals(16f, floatValue(doc, "adapter.lora.alpha"), 1e-6f);
        assertEquals(2, doc.tensors.size());
        GgufDocument.TensorInfo a = tensor(doc, "blk.0.attn_q.weight.lora_a");
        GgufDocument.TensorInfo b = tensor(doc, "blk.0.attn_q.weight.lora_b");
        // GGUF dims are innermost-first: A bytes are rank x in row-major -> [in, rank]
        assertEquals(0, a.type);
        assertEquals(2, a.dimensions.length);
        assertEquals(4L, a.dimensions[0]);
        assertEquals(2L, a.dimensions[1]);
        // B bytes are out x rank row-major -> [rank, out]
        assertEquals(2L, b.dimensions[0]);
        assertEquals(3L, b.dimensions[1]);
        // byte layout is the HF row-major A then B, verbatim; the first
        // tensor's data starts exactly at dataStartOffset (writeHeader aligns
        // each tensor to 64, and A is written first)
        byte[] all = Files.readAllBytes(output);
        byte[] expected = f32(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f,
                9f, 10f, 11f, 12f, 13f, 14f);
        byte[] payload = new byte[expected.length];
        System.arraycopy(all, (int) doc.dataStartOffset, payload, 0, expected.length);
        assertArrayEquals(expected, payload, "A then B must be verbatim F32 LE");
    }

    @Test
    void unknownTrainingLayoutFileFailsTypedListingTheFile() throws Exception {
        Path safetensors = temp.resolve("adapter_somewhere_else.safetensors");
        LoRASafetensors.write(adapter(16), safetensors, Collections.emptyMap());

        java.io.IOException failure = assertThrows(java.io.IOException.class,
                () -> LoraAdapterGguf.trainingLayoutEntries(temp));
        assertTrue(failure.getMessage().contains("adapter_somewhere_else.safetensors"),
                "the failure must name the file: " + failure.getMessage());
    }

    @Test
    void tokenEmbdTargetFailsTyped() throws Exception {
        Path safetensors = temp.resolve("adapter.safetensors");
        LoRASafetensors.write(adapter(16), safetensors, Collections.emptyMap());
        GgufDocument doc = new GgufDocument();
        doc.version = 3;
        doc.upsert("general.architecture", 8, "qwen3");
        byte[] data = f32(new float[8]);
        doc.tensors.add(new GgufDocument.TensorInfo("token_embd.weight",
                new long[]{4, 2}, 0, 0, data.length));
        Path base = temp.resolve("base-embd.gguf");
        try (java.io.OutputStream out = Files.newOutputStream(base)) {
            GgufBinaryWriter writer = new GgufBinaryWriter(out);
            doc.writeHeader(writer);
            writer.write(data);
        }

        java.io.IOException failure = assertThrows(java.io.IOException.class,
                () -> LoraAdapterGguf.export(new LoraAdapterGguf.Request(base,
                        Collections.singletonList(
                                new LoraAdapterGguf.Entry(safetensors, "token_embd.weight")),
                        temp.resolve("adapter.gguf"))));
        assertTrue(failure.getMessage().contains("token_embd.weight"));
    }

    @Test
    void shapeMismatchAgainstBaseFailsTyped() throws Exception {
        Path safetensors = temp.resolve("adapter.safetensors");
        LoRASafetensors.write(adapter(16), safetensors, Collections.emptyMap());
        Path base = baseGguf("qwen3", new long[]{8, 3}); // base in=8, adapter in=4

        java.io.IOException failure = assertThrows(java.io.IOException.class,
                () -> LoraAdapterGguf.export(new LoraAdapterGguf.Request(base,
                        Collections.singletonList(
                                new LoraAdapterGguf.Entry(safetensors, "blk.0.attn_q.weight")),
                        temp.resolve("adapter.gguf"))));
        assertTrue(failure.getMessage().contains("blk.0.attn_q.weight"));
        assertTrue(failure.getMessage().contains("shape")
                || failure.getMessage().contains("dimension"));
    }

    @Test
    void disagreeingAlphasFailTyped() throws Exception {
        Path first = temp.resolve("first.safetensors");
        Path second = temp.resolve("second.safetensors");
        LoRASafetensors.write(adapter(16), first, Collections.emptyMap());
        LoRASafetensors.write(adapter(32), second, Collections.emptyMap());
        Path baseTwo = baseGguf("qwen3", "blk.0.attn_v.weight", new long[]{4, 3});
        // add a second tensor to the same file: rewrite with both
        GgufDocument doc = new GgufDocument();
        doc.version = 3;
        doc.upsert("general.architecture", 8, "qwen3");
        byte[] q = f32(new float[12]);
        byte[] v = f32(new float[12]);
        doc.tensors.add(new GgufDocument.TensorInfo("blk.0.attn_q.weight",
                new long[]{4, 3}, 0, 0, q.length));
        doc.tensors.add(new GgufDocument.TensorInfo("blk.0.attn_v.weight",
                new long[]{4, 3}, 0, 0, v.length));
        try (java.io.OutputStream out = Files.newOutputStream(baseTwo)) {
            GgufBinaryWriter writer = new GgufBinaryWriter(out);
            doc.writeHeader(writer);
            writer.write(q);
            writer.write(v);
        }

        List<LoraAdapterGguf.Entry> entries = new ArrayList<>();
        entries.add(new LoraAdapterGguf.Entry(first, "blk.0.attn_q.weight"));
        entries.add(new LoraAdapterGguf.Entry(second, "blk.0.attn_v.weight"));
        java.io.IOException failure = assertThrows(java.io.IOException.class,
                () -> LoraAdapterGguf.export(new LoraAdapterGguf.Request(baseTwo, entries,
                        temp.resolve("adapter.gguf"))));
        assertTrue(failure.getMessage().contains("alpha"),
                "the failure must name rank/alpha disagreement: " + failure.getMessage());
    }

    private static String stringValue(GgufDocument doc, String key) {
        for (GgufDocument.KeyValue kv : doc.metadata) {
            if (kv.key.equals(key)) {
                return (String) kv.value;
            }
        }
        throw new AssertionError("missing key: " + key);
    }

    private static float floatValue(GgufDocument doc, String key) {
        for (GgufDocument.KeyValue kv : doc.metadata) {
            if (kv.key.equals(key)) {
                return ((Number) kv.value).floatValue();
            }
        }
        throw new AssertionError("missing key: " + key);
    }

    private static GgufDocument.TensorInfo tensor(GgufDocument doc, String name) {
        for (GgufDocument.TensorInfo tensor : doc.tensors) {
            if (tensor.name.equals(name)) {
                return tensor;
            }
        }
        throw new AssertionError("missing tensor: " + name);
    }
}
