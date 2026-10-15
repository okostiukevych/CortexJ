package io.cortexj.train.djl.models;

import ai.djl.Model;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.training.ParameterStore;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CausalGqaAttentionTest {

    @Test
    void attentionIsCausalAndShapedCorrectly() {
        assumeTrue(LoraLinearTest.enginePresent(), "no DJL PyTorch engine - attention skipped loudly");
        Model model = Model.newInstance("attn-test", "PyTorch");
        try {
            NDManager manager = model.getNDManager();
            ArchitectureConfig config = ArchitectureConfigs.fromJson("{"
                    + "\"architectures\":[\"Qwen3ForCausalLM\"],"
                    + "\"hidden_size\":16,\"num_hidden_layers\":2,\"num_attention_heads\":4,"
                    + "\"num_key_value_heads\":2,\"intermediate_size\":32,\"vocab_size\":64,"
                    + "\"max_position_embeddings\":32}");
            Random random = new Random(7L);
            CausalGqaAttention attention = new CausalGqaAttention(config, 0, null, null,
                    null, null);
            attention.loadFrozen(weights(random, 16 * 16), weights(random, 16 * 8),
                    weights(random, 16 * 8), weights(random, 16 * 16),
                    weights(random, 4), weights(random, 4), manager);
            int half = config.headDim() / 2;
            NDArray cos = manager.create(LmOps.ropeCos(32, half, config.ropeTheta()),
                    new Shape(32, half));
            NDArray sin = manager.create(LmOps.ropeSin(32, half, config.ropeTheta()),
                    new Shape(32, half));

            float[] input = new float[2 * 5 * 16];
            for (int i = 0; i < input.length; i++) {
                input[i] = random.nextFloat() - 0.5f;
            }
            NDArray x = manager.create(input, new Shape(2, 5, 16));
            NDArray out = attention.forward(x, cos, sin, manager,
                    new ParameterStore(manager, false), true);
            assertTrue(out.getShape().equals(new Shape(2, 5, 16)), "shape must be (N, T, H)");

            // causality: changing the LAST token must not change earlier positions
            float[] altered = input.clone();
            for (int b = 0; b < 2; b++) {
                for (int h = 0; h < 16; h++) {
                    altered[(b * 5 + 4) * 16 + h] += 1.0f;
                }
            }
            NDArray outAltered = attention.forward(manager.create(altered, new Shape(2, 5, 16)),
                    cos, sin, manager, new ParameterStore(manager, false), true);
            float[] before = out.toFloatArray();
            float[] after = outAltered.toFloatArray();
            for (int b = 0; b < 2; b++) {
                for (int p = 0; p < 4; p++) {
                    for (int h = 0; h < 16; h++) {
                        int index = (b * 5 + p) * 16 + h;
                        assertTrue(Math.abs(before[index] - after[index]) < 1e-4f,
                                "position " + p + " changed when only the last token was altered");
                    }
                }
            }
        } finally {
            model.close();
        }
    }

    private static float[] weights(Random random, int size) {
        float[] values = new float[size];
        for (int i = 0; i < size; i++) {
            values[i] = (random.nextFloat() - 0.5f) * 0.2f;
        }
        return values;
    }
}
