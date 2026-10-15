package io.cortexj.convert.safetensors.test;

import io.cortexj.convert.gguf.internal.GgufBinaryReader;import io.cortexj.convert.gguf.internal.GgufDocument;
import io.cortexj.convert.safetensors.SafetensorsToGgufConverter;
import io.cortexj.convert.safetensors.test.SafetensorsFixture;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.spi.conversion.ConversionRequest;
import io.cortexj.spi.conversion.ConversionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Qwen-family enrichment: HF tensor names become canonical GGUF
 * names, hyperparameters land in metadata, and the BPE tokenizer
 * (tokens, types, merges, specials) is embedded - verified against
 * the official Qwen3 GGUF layout.
 */
class SafetensorsQwenEnrichmentTest {

    @TempDir
    Path tempDir;

    @Test
    void qwenCheckpointConvertsToLoadableLayout() throws Exception {
        Path source = tempDir.resolve("mini.safetensors");
        Files.write(tempDir.resolve("config.json"),
                ("{\"model_type\":\"qwen3\",\"hidden_size\":32,\"num_hidden_layers\":1,"
                        + "\"num_attention_heads\":4,\"num_key_value_heads\":2,"
                        + "\"intermediate_size\":64,\"vocab_size\":8,\"rope_theta\":10000.0,"
                        + "\"rms_norm_eps\":1e-6,\"tie_word_embeddings\":true,"
                        + "\"max_position_embeddings\":128,\"torch_dtype\":\"float16\"}")
                        .getBytes(StandardCharsets.UTF_8));
        Files.write(tempDir.resolve("tokenizer.json"),
                ("{\"model\":{\"type\":\"BPE\",\"vocab\":{\"a\":0,\"b\":1,\"c\":2,\"d\":3,"
                        + "\"e\":4,\"f\":5,\"g\":6,\"h\":7},\"merges\":[\"a b\"]}}")
                        .getBytes(StandardCharsets.UTF_8));
        Files.write(tempDir.resolve("tokenizer_config.json"),
                ("{\"eos_token\":\"h\",\"pad_token\":\"a\",\"add_bos_token\":false,"
                        + "\"added_tokens_decoder\":{}}")
                        .getBytes(StandardCharsets.UTF_8));
        Map<String, SafetensorsFixture.Tensor> tensors =
                new LinkedHashMap<String, SafetensorsFixture.Tensor>();
        tensors.put("model.embed_tokens.weight",
                new SafetensorsFixture.Tensor("F32", new long[]{8, 32}, zeros(8 * 32)));
        tensors.put("model.layers.0.self_attn.q_proj.weight",
                new SafetensorsFixture.Tensor("F32", new long[]{32, 32}, zeros(32 * 32)));
        tensors.put("model.layers.0.self_attn.k_proj.weight",
                new SafetensorsFixture.Tensor("F32", new long[]{32, 16}, zeros(32 * 16)));
        tensors.put("model.layers.0.self_attn.v_proj.weight",
                new SafetensorsFixture.Tensor("F32", new long[]{32, 16}, zeros(32 * 16)));
        tensors.put("model.layers.0.self_attn.o_proj.weight",
                new SafetensorsFixture.Tensor("F32", new long[]{32, 32}, zeros(32 * 32)));
        tensors.put("model.layers.0.self_attn.q_norm.weight",
                new SafetensorsFixture.Tensor("F32", new long[]{8}, zeros(8)));
        tensors.put("model.layers.0.self_attn.k_norm.weight",
                new SafetensorsFixture.Tensor("F32", new long[]{8}, zeros(8)));
        tensors.put("model.layers.0.mlp.gate_proj.weight",
                new SafetensorsFixture.Tensor("F32", new long[]{64, 32}, zeros(64 * 32)));
        tensors.put("model.layers.0.mlp.up_proj.weight",
                new SafetensorsFixture.Tensor("F32", new long[]{64, 32}, zeros(64 * 32)));
        tensors.put("model.layers.0.mlp.down_proj.weight",
                new SafetensorsFixture.Tensor("F32", new long[]{32, 64}, zeros(32 * 64)));
        tensors.put("model.layers.0.input_layernorm.weight",
                new SafetensorsFixture.Tensor("F32", new long[]{32}, zeros(32)));
        tensors.put("model.layers.0.post_attention_layernorm.weight",
                new SafetensorsFixture.Tensor("F32", new long[]{32}, zeros(32)));
        tensors.put("model.norm.weight",
                new SafetensorsFixture.Tensor("F32", new long[]{32}, zeros(32)));
        SafetensorsFixture.write(source, tensors);

        ConversionResult result = new SafetensorsToGgufConverter().convert(
                new ConversionRequest(
                        new ModelArtifact.Abstract("mini", ArtifactFormat.SAFETENSORS,
                                1_000L, null, null) {
                        },
                        source, ArtifactFormat.GGUF, null, tempDir,
                        new LinkedHashMap<String, Object>()));
        Path output = tempDir.resolve("mini.gguf");
        assertTrue(Files.isRegularFile(output));

        GgufDocument document = GgufDocument.read(output);
        List<String> names = new ArrayList<String>();
        for (GgufDocument.TensorInfo tensor : document.tensors) {
            names.add(tensor.name);
        }
        for (String expected : new String[]{"token_embd.weight", "output_norm.weight",
                "blk.0.attn_q.weight", "blk.0.attn_k.weight", "blk.0.attn_v.weight",
                "blk.0.attn_output.weight", "blk.0.attn_q_norm.weight",
                "blk.0.attn_k_norm.weight", "blk.0.ffn_gate.weight", "blk.0.ffn_up.weight",
                "blk.0.ffn_down.weight", "blk.0.attn_norm.weight", "blk.0.ffn_norm.weight"}) {
            assertTrue(names.contains(expected), "missing GGUF tensor: " + expected);
        }
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        for (GgufDocument.KeyValue kv : document.metadata) {
            metadata.put(kv.key, kv.value);
        }
        assertEquals("qwen3", metadata.get("general.architecture"));
        assertEquals(64L, ((Number) metadata.get("qwen3.feed_forward_length")).longValue());
        assertEquals(2L, ((Number) metadata.get("qwen3.attention.head_count_kv")).longValue());
        assertEquals(8L, ((Number) metadata.get("qwen3.attention.key_length")).longValue());
        assertEquals("qwen2", metadata.get("tokenizer.ggml.pre"));
        GgufBinaryReader.ArrayValue tokens =
                (GgufBinaryReader.ArrayValue) metadata.get("tokenizer.ggml.tokens");
        assertEquals(8, tokens.values.size());
        assertEquals("a", tokens.values.get(0));
        GgufBinaryReader.ArrayValue merges =
                (GgufBinaryReader.ArrayValue) metadata.get("tokenizer.ggml.merges");
        assertEquals(1, merges.values.size());
        assertEquals(7L, ((Number) metadata.get("tokenizer.ggml.eos_token_id")).longValue());
        assertEquals(7L, ((Number) metadata.get("tokenizer.ggml.bos_token_id")).longValue());
    }

    private static byte[] zeros(int floats) {
        return SafetensorsFixture.f32(new float[floats]);
    }
}
