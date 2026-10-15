package io.cortexj.train.djl.models;

import ai.djl.Model;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LmOpsTest {

    @Test
    void ropeTablesStartAtOneAndRotateByInvFrequency() {
        int half = 4;
        float[] cos = LmOps.ropeCos(8, half, 10000.0);
        float[] sin = LmOps.ropeSin(8, half, 10000.0);
        assertEquals(8 * half, cos.length);
        for (int d = 0; d < half; d++) {
            assertEquals(1.0f, cos[d], 1e-6f);
            assertEquals(0.0f, sin[d], 1e-6f);
        }
        assertEquals((float) Math.cos(1.0), cos[half], 1e-5f);
        assertEquals((float) Math.sin(1.0), sin[half], 1e-5f);
    }

    @Test
    void blockMathMatchesHandComputedValues() {
        assumeTrue(LoraLinearTest.enginePresent(), "no DJL PyTorch engine - ops skipped loudly");
        Model model = Model.newInstance("ops-test", "PyTorch");
        try {
            NDManager manager = model.getNDManager();
            NDArray x = manager.create(new float[]{1f, 2f, 3f, 4f}, new Shape(1, 2, 2));
            NDArray ones = manager.ones(new Shape(2), ai.djl.ndarray.types.DataType.FLOAT32);
            NDArray normed = LmOps.rmsNorm(x, ones, 1e-6f);
            // per position: x * x / sqrt(mean(x^2) + eps)
            float[] expected = new float[]{0.6324555f, 1.2649111f, 0.8485281f, 1.1313708f};
            assertArrayEquals(expected, normed.toFloatArray(), 1e-5f);

            NDArray zero = manager.create(new float[]{0f}, new Shape(1));
            assertEquals(0f, LmOps.silu(zero).toFloatArray()[0], 1e-6f);
            NDArray one = manager.create(new float[]{1f}, new Shape(1));
            assertEquals(0.7310586f, LmOps.silu(one).toFloatArray()[0], 1e-5f);

            NDArray logits = manager.create(new float[]{0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f},
                    new Shape(1, 1, 3, 3));
            NDArray masked = LmOps.causalScores(logits, manager, 3);
            float[] scores = masked.toFloatArray();
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    assertTrue((j <= i ? 0f : -1e9f) == scores[i * 3 + j],
                            "position " + i + " must not attend to " + j);
                }
            }
        } finally {
            model.close();
        }
    }

    @Test
    void gqaExpansionInterleavesInsteadOfTiling() {
        assumeTrue(LoraLinearTest.enginePresent(), "no DJL PyTorch engine - ops skipped loudly");
        Model model = Model.newInstance("gqa-test", "PyTorch");
        try {
            NDManager manager = model.getNDManager();
            // (1, 2 kv heads, 1 pos, 2 dims): kv0 = [1, 2], kv1 = [3, 4]
            NDArray kv = manager.create(new float[]{1f, 2f, 3f, 4f}, new Shape(1, 2, 1, 2));
            NDArray expanded = LmOps.repeatInterleaveHeads(kv, 2, 2);
            assertArrayEquals(new float[]{1f, 2f, 1f, 2f, 3f, 4f, 3f, 4f},
                    expanded.toFloatArray(), 1e-6f);
        } finally {
            model.close();
        }
    }

    @Test
    void siluStaysFiniteFarIntoTheTails() {
        assumeTrue(LoraLinearTest.enginePresent(), "no DJL PyTorch engine - ops skipped loudly");
        Model model = Model.newInstance("silu-tail-test", "PyTorch");
        try {
            NDManager manager = model.getNDManager();
            NDArray x = manager.create(new float[]{-100f, -1000f, 100f}, new Shape(3));
            NDArray y = LmOps.silu(x);
            for (float value : y.toFloatArray()) {
                assertTrue(Float.isFinite(value), "silu forward must stay finite");
            }
            assertEquals(0f, y.toFloatArray()[0], 1e-6f);
            assertEquals(0f, y.toFloatArray()[1], 1e-6f);
            assertEquals(100f, y.toFloatArray()[2], 1.0f);
        } finally {
            model.close();
        }
    }

    @Test
    void siluBackwardStaysFinite() {
        assumeTrue(LoraLinearTest.enginePresent(), "no DJL PyTorch engine - ops skipped loudly");
        Model model = Model.newInstance("silu-grad-test", "PyTorch");
        try {
            ai.djl.nn.SequentialBlock block = new ai.djl.nn.SequentialBlock();
            block.add(ai.djl.nn.core.Linear.builder().setUnits(1).build());
            model.setBlock(block);
            ai.djl.training.Trainer trainer = model.newTrainer(
                    new ai.djl.training.DefaultTrainingConfig(
                            ai.djl.training.loss.Loss.l2Loss()));
            trainer.initialize(new Shape(3));
            NDManager manager = model.getNDManager();
            NDArray x = manager.create(new float[]{-100f, -1000f, 100f}, new Shape(3));
            x.setRequiresGradient(true);
            try (ai.djl.training.GradientCollector gc = trainer.newGradientCollector()) {
                NDArray y = LmOps.silu(x);
                gc.backward(y.sum());
            }
            float[] grad = x.getGradient().toFloatArray();
            for (float value : grad) {
                assertTrue(Float.isFinite(value),
                        "silu backward must stay finite (was NaN via 0*Inf)");
            }
            assertEquals(0f, grad[0], 1e-3f);
            assertEquals(1f, grad[2], 1e-3f);
        } finally {
            model.close();
        }
    }

    @Test
    void ropeWithIdentityTablesLeavesInputUnchanged() {
        assumeTrue(LoraLinearTest.enginePresent(), "no DJL PyTorch engine - ops skipped loudly");
        Model model = Model.newInstance("rope-test", "PyTorch");
        try {
            NDManager manager = model.getNDManager();
            NDArray x = manager.create(new float[]{1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f},
                    new Shape(1, 2, 2, 2)); // last dim = headDim 2, half = 1
            NDArray cos = manager.ones(new Shape(1, 1, 2, 1), ai.djl.ndarray.types.DataType.FLOAT32);
            NDArray sin = manager.zeros(new Shape(1, 1, 2, 1), ai.djl.ndarray.types.DataType.FLOAT32);
            NDArray identity = LmOps.applyRope(x, cos, sin, 2);
            assertArrayEquals(x.toFloatArray(), identity.toFloatArray(), 1e-6f);
        } finally {
            model.close();
        }
    }
}
