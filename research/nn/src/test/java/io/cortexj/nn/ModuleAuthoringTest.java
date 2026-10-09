package io.cortexj.nn;

import io.cortexj.autograd.Autograd;
import io.cortexj.autograd.Variable;
import io.cortexj.tensor.Shape;
import io.cortexj.tensor.TensorBackend;
import io.cortexj.tensor.cpu.CpuBackend;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A researcher authors a new architecture entirely in JVM source
 * (custom attention-style scoring) without modifying the framework.
 */
class ModuleAuthoringTest {

    /** Custom "attention scoring" module written in the test itself. */
    static final class AttentionScoring extends TensorModule {

        private final Autograd autograd;
        private final Variable weights;

        AttentionScoring(Autograd autograd, int units) {
            this.autograd = autograd;
            double[] initial = new double[units];
            for (int i = 0; i < units; i++) {
                initial[i] = 0.5;
            }
            this.weights = register("weight", autograd.variable(initial, Shape.of(units)));
        }

        @Override
        public Object forward(Object input) {
            Variable scores = (Variable) input;
            Variable scaled = autograd.mul(scores, weights);
            return autograd.softmax(scaled);
        }

        @Override
        public String toString() {
            return repr();
        }
    }

    private static final TensorBackend BACKEND = new CpuBackend();

    @Test
    void customModuleTrainsWithoutCoreChanges() {
        Autograd autograd = new Autograd(BACKEND);
        AttentionScoring module = new AttentionScoring(autograd, 4);
        Variable scores = autograd.constant(new double[]{2.0, 1.0, 0.5, -1.0}, Shape.of(4));
        Variable output = (Variable) module.forward(scores);
        // weighted loss: sum of softmax is constant (derivative identically
        // zero), so the loss takes the first probability mass instead
        Variable target = autograd.constant(new double[]{1.0, 0.0, 0.0, 0.0}, Shape.of(4));
        Variable loss = autograd.sum(autograd.mul(output, target));
        autograd.backward(loss);

        double[] grad = module.parameters().get(0).grad();
        assertEquals(4, grad.length);
        // gradient flows into the custom module's parameter
        assertTrue(grad[0] != 0.0);
        // notebook-friendly repr shows the tree
        assertTrue(module.repr().contains("AttentionScoring"), module.repr());
        assertTrue(module.repr().contains("1 params"), module.repr());
    }

    @Test
    void stateDictRoundTripKeepsValues() {
        Autograd autograd = new Autograd(BACKEND);
        AttentionScoring module = new AttentionScoring(autograd, 4);
        Map<String, double[]> state = module.stateDict();
        assertEquals(1, state.size());
        assertTrue(state.containsKey("weight"));
        assertEquals(0.5, state.get("weight")[0], 1e-12);

        // load: explicit write of the same values (idempotent round-trip)
        module.loadStateDict(new LinkedHashMap<>(state));
        assertEquals(0.5, module.stateDict().get("weight")[0], 1e-12);
    }

    @Test
    void stateDictMissingOrWrongSizeFailsTyped() {
        Autograd autograd = new Autograd(BACKEND);
        AttentionScoring module = new AttentionScoring(autograd, 4);
        assertThrows(IllegalArgumentException.class,
                () -> module.loadStateDict(new LinkedHashMap<>()));
        assertThrows(IllegalArgumentException.class,
                () -> module.loadStateDict(Map.of("weight", new double[]{1.0})));
    }

    @Test
    void trainEvalModePropagates() {
        Autograd autograd = new Autograd(BACKEND);
        AttentionScoring module = new AttentionScoring(autograd, 4);
        assertTrue(module.training());
        module.training(false);
        assertTrue(!module.training());
    }
}
