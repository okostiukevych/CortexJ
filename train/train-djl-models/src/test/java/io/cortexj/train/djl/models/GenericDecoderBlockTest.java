package io.cortexj.train.djl.models;

import ai.djl.Model;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.Shape;
import ai.djl.training.DefaultTrainingConfig;
import ai.djl.training.Trainer;
import ai.djl.training.loss.Loss;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GenericDecoderBlockTest {

    @TempDir
    Path temp;

    @Test
    void forwardProducesFiniteLogitsOfVocabShape() throws IOException {
        assumeTrue(LoraLinearTest.enginePresent(), "no DJL PyTorch engine - forward skipped loudly");
        MiniModelFixture fixture = new MiniModelFixture(temp);
        ArchitectureConfig config = fixture.config();
        GenericDecoderBlock block = GenericDecoderBlock.build(config,
                Collections.<String>emptySet(), 0, 0.0f);
        Model model = Model.newInstance("mini-qwen3", "PyTorch");
        try {
            model.setBlock(block);
            Trainer trainer = model.newTrainer(new DefaultTrainingConfig(Loss.l2Loss()));
            trainer.initialize(new Shape(1, 16));
            block.loadWeights(HfWeightLoader.load(fixture.directory()), model.getNDManager());
            block.initLora(42L);

            int[] ids = new int[16];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = (i * 7) % config.vocabSize();
            }
            NDList logits = trainer.forward(new NDList(
                    trainer.getManager().create(ids, new Shape(1, 16))));
            assertEquals(new Shape(1, 16, config.vocabSize()),
                    logits.singletonOrThrow().getShape());
            for (float value : logits.singletonOrThrow().toFloatArray()) {
                assertTrue(Float.isFinite(value), "logits must be finite");
            }
        } finally {
            model.close();
        }
    }

    @Test
    void loraTargetsBecomeTrainableChildrenWithStableNames() {
        GenericDecoderBlock block = GenericDecoderBlock.build(
                ArchitectureConfigs.fromJson(MiniModelFixture.CONFIG_JSON),
                java.util.Collections.singleton("q_proj"), 4, 2.0f);
        Map<String, LoraLinear> children = block.loraChildren();
        assertEquals(2, children.size(), "one q_proj wrapper per layer");
        assertTrue(children.containsKey("layers.0.self_attn.q_proj"));
        assertTrue(children.containsKey("layers.1.self_attn.q_proj"));
        assertEquals(4, children.get("layers.0.self_attn.q_proj").rank());
    }

    @Test
    void missingWeightsDirectoryFailsTyped() throws IOException {
        Path empty = Files.createDirectories(temp.resolve("empty"));
        Files.write(empty.resolve("config.json"),
                MiniModelFixture.CONFIG_JSON.getBytes(StandardCharsets.UTF_8));
        io.cortexj.error.CortexJException failure = assertThrows(
                io.cortexj.error.CortexJException.class,
                () -> HfWeightLoader.load(empty));
        assertEquals("CORTEXJ-TRAIN-0103", failure.code());
        assertTrue(failure.getMessage().contains("model.safetensors"));
    }

    @Test
    void nameMappingMatchesExpectedHfTensors() {
        ArchitectureConfig config = ArchitectureConfigs.fromJson(MiniModelFixture.CONFIG_JSON);
        List<String> expected = HfNames.expected(config);
        assertTrue(expected.contains("model.embed_tokens.weight"));
        assertTrue(expected.contains("model.layers.1.self_attn.q_proj.weight"));
        assertTrue(expected.contains("model.layers.1.self_attn.q_norm.weight"));
        assertTrue(expected.contains("model.layers.0.mlp.down_proj.weight"));
        assertTrue(expected.contains("model.norm.weight"));
        assertTrue(expected.contains("lm_head.weight"), "untied head is expected");
        assertEquals(1 + 2 * 11 + 1 + 1, expected.size());
        List<String> qw25 = HfNames.expected(ArchitectureConfigs.fromJson(
                "{\"architectures\":[\"Qwen2.5ForCausalLM\"],\"hidden_size\":32,"
                        + "\"num_hidden_layers\":2,\"num_attention_heads\":4,"
                        + "\"num_key_value_heads\":2,\"intermediate_size\":64,"
                        + "\"vocab_size\":64,\"tie_word_embeddings\":false}"));
        assertTrue(qw25.contains("model.layers.0.self_attn.q_proj.bias"),
                "QWEN2 preset expects q/k/v biases");
        assertTrue(!qw25.contains("model.layers.0.self_attn.o_proj.bias"),
                "o_proj carries no bias in the QWEN2 preset");
        assertTrue(!qw25.contains("model.layers.0.self_attn.q_norm.weight"),
                "QWEN2 preset has no QK-norm");
    }
}
