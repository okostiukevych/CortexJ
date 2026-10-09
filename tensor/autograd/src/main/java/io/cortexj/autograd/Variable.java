package io.cortexj.autograd;

import io.cortexj.tensor.Shape;
import io.cortexj.tensor.Tensor;

import java.util.Arrays;

/**
 * One differentiable value: tensor data plus the accumulated gradient.
 * Leaf variables carry {@code requiresGradient}; results of tracked ops
 * are non-leaf. detach() drops the tracking (ADR-026).
 */
public final class Variable {

    private final Tensor data;
    private final boolean requiresGradient;
    private double[] grad;

    Variable(Tensor data, boolean requiresGradient) {
        if (data == null) {
            throw new IllegalArgumentException("tensor data must not be null");
        }
        this.data = data;
        this.requiresGradient = requiresGradient;
    }

    public static Variable constant(Tensor data) {
        return new Variable(data, false);
    }

    public static Variable parameter(Tensor data) {
        return new Variable(data, true);
    }

    public Shape shape() {
        return data.shape();
    }

    public Tensor data() {
        return data;
    }

    public boolean requiresGradient() {
        return requiresGradient;
    }

    /**
     * The accumulated gradient (zeros while backward has not run); null
     * for variables that do not require gradient.
     */
    public double[] grad() {
        if (!requiresGradient) {
            return null;
        }
        if (grad == null) {
            return new double[(int) data.shape().size()];
        }
        return grad.clone();
    }

    void accumulateGradient(double[] values) {
        if (values.length != (int) data.shape().size()) {
            throw new IllegalArgumentException("gradient shape mismatch");
        }
        if (grad == null) {
            grad = values.clone();
            return;
        }
        for (int i = 0; i < grad.length; i++) {
            grad[i] += values[i];
        }
    }

    double[] gradInternal() {
        return grad;
    }

    void seedGradient(double[] values) {
        grad = values.clone();
    }

    /**
     * A detached copy: same data, no gradient tracking.
     */
    public Variable detach() {
        return new Variable(data, false);
    }

    @Override
    public String toString() {
        return "Variable" + data.shape() + (requiresGradient ? "*" : "");
    }
}
