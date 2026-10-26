package io.cortexj.nn;

import io.cortexj.autograd.Autograd;
import io.cortexj.autograd.Variable;
import io.cortexj.tensor.Shape;
import io.cortexj.tensor.TensorBackend;
import io.cortexj.tensor.cpu.CpuBackend;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reusable layers (Linear) and initialization policies: deterministic
 * per seed, hand-computed values.
 */
class LinearLayerTest {

    private static final TensorBackend BACKEND = new CpuBackend();

    @Test
    void linearForwardIsHandComputed() {
        Autograd autograd = new Autograd(BACKEND);
        Linear layer = new Linear(autograd, 2, 2, Initialization.constant(0.5));
        // weight = [[0.5, 0.5], [0.5, 0.5]], bias = [0, 0]
        Variable x = autograd.constant(new double[]{1.0, 2.0}, Shape.of(2, 1));
        Variable y = (Variable) layer.forward(x);
        // y[0] = 0.5*1 + 0.5*2 = 1.5
        assertEquals(1.5, y.data().get(0, 0), 1e-9);
        assertEquals(1.5, y.data().get(1, 0), 1e-9);
        // bias shifts per row across batch columns, and backward propagates into bias
        layer.loadStateDict(Map.of("weight", new double[]{0.5, 0.5, 0.5, 0.5},
                "bias", new double[]{1.0, 2.0}));
        Variable batchX = autograd.constant(new double[]{1.0, 2.0, 3.0, 4.0}, Shape.of(2, 2));
        Variable shifted = (Variable) layer.forward(batchX);
        // row 0: [0.5*1 + 0.5*3 + 1.0, 0.5*2 + 0.5*4 + 1.0] = [3.0, 4.0]
        // row 1: [0.5*1 + 0.5*3 + 2.0, 0.5*2 + 0.5*4 + 2.0] = [4.0, 5.0]
        assertEquals(3.0, shifted.data().get(0, 0), 1e-9);
        assertEquals(4.0, shifted.data().get(0, 1), 1e-9);
        assertEquals(4.0, shifted.data().get(1, 0), 1e-9);
        assertEquals(5.0, shifted.data().get(1, 1), 1e-9);
        Variable loss = autograd.sum(shifted);
        autograd.backward(loss);
        assertEquals(2.0, layer.bias().grad()[0], 1e-9);
        assertEquals(2.0, layer.bias().grad()[1], 1e-9);
    }

    @Test
    void initializationIsDeterministicPerSeed() {
        var first = Initialization.uniform(1.0, 42L);
        var second = Initialization.uniform(1.0, 42L);
        // materialize both sequences first: drawing inside the assert loop
        // would advance one Random ahead of the other
        double[] left = new double[8];
        double[] right = new double[8];
        for (int i = 0; i < 8; i++) {
            left[i] = first.valueAt(i);
            right[i] = second.valueAt(i);
        }
        for (int i = 0; i < 8; i++) {
            assertEquals(left[i], right[i], 1e-12);
            assertTrue(Math.abs(left[i]) <= 1.0);
        }
        // xavier bound: sqrt(6 / (fanIn + fanOut))
        var xavier = Initialization.xavier(4, 2, 7L);
        double bound = Math.sqrt(6.0 / 6.0);
        for (int i = 0; i < 8; i++) {
            assertTrue(Math.abs(xavier.valueAt(i)) <= bound);
        }
    }

    @Test
    void stateDictCoversWeightAndBias() {
        Autograd autograd = new Autograd(BACKEND);
        Linear layer = new Linear(autograd, 2, 3, Initialization.zeros());
        Map<String, double[]> state = layer.stateDict();
        assertEquals(2, state.size());
        assertTrue(state.containsKey("weight"));
        assertTrue(state.containsKey("bias"));
        assertEquals(6, state.get("weight").length);
        assertEquals(3, state.get("bias").length);
        layer.loadStateDict(state);
    }

    @Test
    void layerRegistersThroughModuleApi() {
        Autograd autograd = new Autograd(BACKEND);
        Linear layer = new Linear(autograd, 2, 2, Initialization.zeros());
        assertEquals(2, layer.parameters().size());
        assertTrue(layer.repr().contains("Linear"), layer.repr());
        assertTrue(layer.repr().contains("2 params"), layer.repr());
    }

    @Test
    void invalidSizesAreRejected() {
        Autograd autograd = new Autograd(BACKEND);
        assertThrows(IllegalArgumentException.class,
                () -> new Linear(autograd, 0, 2, Initialization.zeros()));
        assertThrows(IllegalArgumentException.class,
                () -> new Linear(autograd, 2, 2, null));
    }
}
