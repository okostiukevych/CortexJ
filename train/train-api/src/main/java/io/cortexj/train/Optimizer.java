package io.cortexj.train;

/**
 * Optimization strategy: how gradients become parameter updates.
 * Implementations are pure math; engines may substitute their own.
 */
public interface Optimizer {

    String id();

    /**
     * Applies one update step: parameters move by the scaled gradients.
     * Returns the new values; the input array stays untouched.
     */
    double[] step(double[] parameters, double[] gradients);
}
