package io.cortexj.train.djl.models;

import ai.djl.Model;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.training.DefaultTrainingConfig;
import ai.djl.training.Trainer;
import ai.djl.training.loss.Loss;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LoraLinearTest {

    @Test
    void loraStartsIdenticalToTheFrozenBase() {
        assumeTrue(enginePresent(), "no DJL PyTorch engine - LoRA contract skipped loudly");
        Model model = Model.newInstance("lora-test", "PyTorch");
        try {
            NDManager manager = model.getNDManager();
            LoraLinear lora = new LoraLinear(2, 3, 4, 2.0f);
            model.setBlock(lora);
            Trainer trainer = model.newTrainer(new DefaultTrainingConfig(Loss.l2Loss()));
            trainer.initialize(new Shape(1, 2));
            // frozen W (out=3, in=2): [[1,0],[0,1],[0.5,0.5]], no bias
            float[] frozen = {1f, 0f, 0f, 1f, 0.5f, 0.5f};
            lora.loadFrozen(frozen, null, manager);
            lora.initLora(42L);

            float[] input = {0.7f, -0.3f};
            NDArray x = manager.create(input, new Shape(1, 2));
            NDList output = trainer.forward(new NDList(x));
            float[] expected = new float[3];
            for (int o = 0; o < 3; o++) {
                for (int i = 0; i < 2; i++) {
                    expected[o] += frozen[o * 2 + i] * input[i];
                }
            }
            assertArrayEquals(expected, output.singletonOrThrow().toFloatArray(), 1e-5f,
                    "B=0 init must make the wrapped module exactly the frozen base");
        } finally {
            model.close();
        }
    }

    static boolean enginePresent() {
        try {
            for (String engine : ai.djl.engine.Engine.getAllEngines()) {
                if ("PyTorch".equals(engine)) {
                    return true;
                }
            }
        } catch (Throwable unavailable) {
            return false;
        }
        return false;
    }
}
