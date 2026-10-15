package io.cortexj.train.djl.models;

import io.cortexj.convert.safetensors.test.SafetensorsFixture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Synthetic QWEN3-preset mini model (hidden 32, 2 layers, 4 heads,
 * 2 kv heads, vocab 64, untied head, maxPos 128) written to a temp
 * directory: config.json + model.safetensors with every expected
 * tensor, values from a fixed seed. No downloads, fully deterministic.
 */
public final class MiniModelFixture {

    public static final String CONFIG_JSON = "{"
            + "\"architectures\":[\"Qwen3ForCausalLM\"],"
            + "\"hidden_size\":32,"
            + "\"num_hidden_layers\":2,"
            + "\"num_attention_heads\":4,"
            + "\"num_key_value_heads\":2,"
            + "\"intermediate_size\":64,"
            + "\"vocab_size\":64,"
            + "\"max_position_embeddings\":128,"
            + "\"rope_theta\":10000.0,"
            + "\"rms_norm_eps\":1e-6,"
            + "\"tie_word_embeddings\":false}";

    private final Path directory;

    public MiniModelFixture(Path base) throws IOException {
        this(base, false);
    }

    /** Tied variant: tie_word_embeddings=true but lm_head.weight still shipped. */
    public MiniModelFixture(Path base, boolean tied) throws IOException {
        directory = Files.createDirectories(
                base.resolve(tied ? "mini-qwen3-tied" : "mini-qwen3"));
        Files.write(directory.resolve("config.json"),
                configJson(tied).getBytes(StandardCharsets.UTF_8));
        writeWeights(new Random(1234L));
    }

    public Path directory() {
        return directory;
    }

    public ArchitectureConfig config() {
        return ArchitectureConfigs.parse(directory.resolve("config.json"));
    }

    private static String configJson(boolean tied) {
        return CONFIG_JSON.replace("\"tie_word_embeddings\":false",
                "\"tie_word_embeddings\":" + tied);
    }

    private void writeWeights(Random random) throws IOException {
        Map<String, SafetensorsFixture.Tensor> tensors =
                new LinkedHashMap<String, SafetensorsFixture.Tensor>();
        tensors.put("model.embed_tokens.weight", tensor(random, 64 * 32, new long[]{64, 32}));
        for (int layer = 0; layer < 2; layer++) {
            tensors.put("model.layers." + layer + ".self_attn.q_proj.weight",
                    tensor(random, 32 * 32, new long[]{32, 32}));
            tensors.put("model.layers." + layer + ".self_attn.k_proj.weight",
                    tensor(random, 32 * 16, new long[]{32, 16}));
            tensors.put("model.layers." + layer + ".self_attn.v_proj.weight",
                    tensor(random, 32 * 16, new long[]{32, 16}));
            tensors.put("model.layers." + layer + ".self_attn.o_proj.weight",
                    tensor(random, 32 * 32, new long[]{32, 32}));
            tensors.put("model.layers." + layer + ".self_attn.q_norm.weight",
                    ones(8));
            tensors.put("model.layers." + layer + ".self_attn.k_norm.weight",
                    ones(8));
            tensors.put("model.layers." + layer + ".mlp.gate_proj.weight",
                    tensor(random, 64 * 32, new long[]{64, 32}));
            tensors.put("model.layers." + layer + ".mlp.up_proj.weight",
                    tensor(random, 64 * 32, new long[]{64, 32}));
            tensors.put("model.layers." + layer + ".mlp.down_proj.weight",
                    tensor(random, 32 * 64, new long[]{32, 64}));
            tensors.put("model.layers." + layer + ".input_layernorm.weight",
                    ones(32));
            tensors.put("model.layers." + layer + ".post_attention_layernorm.weight",
                    ones(32));
        }
        tensors.put("model.norm.weight", ones(32));
        tensors.put("lm_head.weight", tensor(random, 64 * 32, new long[]{64, 32}));
        SafetensorsFixture.write(directory.resolve("model.safetensors"), tensors);
    }

    private static SafetensorsFixture.Tensor tensor(Random random, int size, long[] shape) {
        float[] values = new float[size];
        for (int i = 0; i < size; i++) {
            values[i] = (random.nextFloat() - 0.5f) * 0.1f;
        }
        return new SafetensorsFixture.Tensor("F32", shape, SafetensorsFixture.f32(values));
    }

    /**
     * Norm weights are ones (like real checkpoints, where RMSNorm
     * weights initialize near 1.0): random ~0.05 norm weights crush
     * every activation and leave LoRA with no learning signal.
     */
    private static SafetensorsFixture.Tensor ones(int size) {
        float[] values = new float[size];
        java.util.Arrays.fill(values, 1.0f);
        long[] shape = new long[]{size};
        return new SafetensorsFixture.Tensor("F32", shape, SafetensorsFixture.f32(values));
    }
}
