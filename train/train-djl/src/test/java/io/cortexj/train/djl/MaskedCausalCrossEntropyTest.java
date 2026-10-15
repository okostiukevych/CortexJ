package io.cortexj.train.djl;

import ai.djl.Model;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.Shape;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MaskedCausalCrossEntropyTest {

    @Test
    void maskedCeMatchesTheHandComputedValue() {
        assumeTrue(enginePresent(), "no DJL PyTorch engine - loss contract skipped loudly");
        Model model = Model.newInstance("ce-test", "PyTorch");
        try {
            NDArray logits = model.getNDManager().create(new float[]{
                    0f, 0f,           // position 0: predicts token 0 (masked out)
                    (float) Math.log(2), (float) Math.log(0.5) // position 1: predicts token 0
            }, new Shape(1, 2, 2));
            NDArray labels = model.getNDManager().create(new long[]{0, 0}, new Shape(1, 2));
            NDArray mask = model.getNDManager().create(new float[]{0f, 1f}, new Shape(1, 2));
            NDArray loss = new MaskedCausalCrossEntropy()
                    .evaluate(new NDList(labels, mask), new NDList(logits));
            // position 1 softmax over [log2, log(1/2)] = [0.8, 0.2]; label 0 -> -log(0.8)
            assertEquals(-Math.log(0.8), loss.getFloat(), 1e-5);
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
