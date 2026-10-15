package io.cortexj.train.djl.models;

import io.cortexj.error.CortexJException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchitectureConfigsTest {

    private static final String QWEN3_JSON = "{"
            + "\"architectures\":[\"Qwen3ForCausalLM\"],"
            + "\"hidden_size\":1024,"
            + "\"num_hidden_layers\":28,"
            + "\"num_attention_heads\":16,"
            + "\"num_key_value_heads\":8,"
            + "\"intermediate_size\":3072,"
            + "\"vocab_size\":151936,"
            + "\"max_position_embeddings\":40960,"
            + "\"rope_theta\":1000000.0,"
            + "\"rms_norm_eps\":1e-6,"
            + "\"tie_word_embeddings\":true}";

    @Test
    void qwen3ConfigParsesToPreset() {
        ArchitectureConfig config = ArchitectureConfigs.fromJson(QWEN3_JSON);
        assertEquals(DecoderFamily.QWEN3, config.family());
        assertEquals(1024, config.hiddenSize());
        assertEquals(28, config.numLayers());
        assertEquals(16, config.numHeads());
        assertEquals(8, config.numKvHeads());
        assertEquals(3072, config.intermediateSize());
        assertEquals(151936, config.vocabSize());
        assertEquals(1_000_000.0, config.ropeTheta(), 1e-9);
        assertTrue(config.tieEmbeddings());
        assertTrue(config.qkNorm());
        assertEquals(64, config.headDim());
        assertEquals(64, config.kvHeadDim());
    }

    @Test
    void explicitHeadDimWinsOverTheQuotient() {
        ArchitectureConfig config = ArchitectureConfigs.fromJson("{"
                + "\"architectures\":[\"Qwen3ForCausalLM\"],"
                + "\"hidden_size\":1024,\"num_hidden_layers\":28,\"num_attention_heads\":16,"
                + "\"num_key_value_heads\":8,\"head_dim\":128,\"intermediate_size\":3072,"
                + "\"vocab_size\":151936}");
        assertEquals(128, config.headDim());
        assertEquals(128, config.kvHeadDim());
        assertEquals(8 * 128, config.kvDim());
    }

    @Test
    void unknownArchitectureFailsTypedListingSupported() {
        CortexJException failure = assertThrows(CortexJException.class,
                () -> ArchitectureConfigs.fromJson("{\"architectures\":[\"FancyForCausalLM\"]}"));
        assertEquals("CORTEXJ-TRAIN-0101", failure.code());
        assertTrue(failure.getMessage().contains("FancyForCausalLM"));
        assertTrue(failure.toString().contains("Qwen3ForCausalLM"));
    }

    @Test
    void missingFieldsFailTypedWithEveryMissingFieldListed() {
        CortexJException failure = assertThrows(CortexJException.class,
                () -> ArchitectureConfigs.fromJson("{\"architectures\":[\"Qwen3ForCausalLM\"]}"));
        assertEquals("CORTEXJ-TRAIN-0102", failure.code());
        for (String field : new String[]{"hidden_size", "num_hidden_layers",
                "num_attention_heads", "intermediate_size", "vocab_size"}) {
            assertTrue(failure.toString().contains(field), "missing field must be listed: " + field);
        }
    }

    @Test
    void paramCountCoversEmbeddingAttentionMlpAndHead() {
        ArchitectureConfig config = ArchitectureConfigs.fromJson(QWEN3_JSON);
        long kv = 1024 / 16 * 8;
        long perLayer = 1024 * 1024          // q
                + 1024 * kv * 2              // k, v
                + 1024 * 1024                // o
                + 1024 * 3072 * 3            // gate, up, down
                + 1024 * 2                   // input/post norms
                + 64 * 2;                    // q_norm, k_norm (QWEN3 QK-norm)
        long expected = 151936L * 1024L      // tied embedding (no separate head)
                + 28 * perLayer
                + 1024;                      // final norm
        assertEquals(expected, ArchitectureConfigs.paramCount(config));
    }
}
