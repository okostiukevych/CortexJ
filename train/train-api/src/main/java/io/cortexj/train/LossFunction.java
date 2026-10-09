package io.cortexj.train;

/**
 * Training objective. Implementations are pure math and unit-tested
 * against hand-computed values.
 */
public interface LossFunction {

    String id();

    /**
     * The scalar loss over one prediction/target pair.
     */
    double loss(double[] prediction, double[] target);

    /**
     * The gradient of the loss with respect to the prediction.
     */
    double[] gradient(double[] prediction, double[] target);
}
