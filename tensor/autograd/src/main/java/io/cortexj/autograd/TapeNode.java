package io.cortexj.autograd;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One node of the tape: output, inputs and the backward rule returning
 * the gradient per input. Built by the tracked ops; custom rules use
 * the same shape (ADR-026).
 */
public final class TapeNode {

    private final Variable output;
    private final List<Variable> inputs;
    private final Backward backward;

    TapeNode(Variable output, List<Variable> inputs, Backward backward) {
        this.output = output;
        this.inputs = new ArrayList<Variable>(inputs);
        this.backward = backward;
    }

    public Variable output() {
        return output;
    }

    public List<Variable> inputs() {
        return inputs;
    }

    public void applyBackward(double[] gradOutput) {
        if (gradOutput == null) {
            // the output gradient was never seeded: nothing to propagate
            return;
        }
        double[][] gradInputs = backward.apply(gradOutput);
        if (gradInputs == null) {
            return;
        }
        for (int i = 0; i < inputs.size() && i < gradInputs.length; i++) {
            Variable input = inputs.get(i);
            if (input.requiresGradient() && gradInputs[i] != null) {
                input.accumulateGradient(gradInputs[i]);
            }
        }
    }

    /**
     * Custom backward rule: output gradient in, gradients per input out.
     */
    public interface Backward {

        double[][] apply(double[] gradOutput);
    }
}
