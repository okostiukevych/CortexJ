package io.cortexj.nn;

import io.cortexj.autograd.Autograd;
import io.cortexj.autograd.Variable;
import io.cortexj.tensor.Shape;

/**
 * A reusable linear (fully-connected) layer: weight (out x in) and
 * bias (out), built through an {@link Autograd} and the
 * {@link Initialization} policies.
 */
public final class Linear extends TensorModule {

    private final Autograd autograd;
    private final Variable weight;
    private final Variable bias;

    public Linear(Autograd autograd, int inputSize, int outputSize,
                  InitializationPolicy policy) {
        if (inputSize < 1 || outputSize < 1) {
            throw new IllegalArgumentException("layer sizes must be positive");
        }
        if (policy == null) {
            throw new IllegalArgumentException("initialization policy is required");
        }
        this.autograd = autograd;
        this.weight = Initialization.parameter(
                autograd, "weight", this, Shape.of(outputSize, inputSize), policy);
        this.bias = Initialization.parameter(
                autograd, "bias", this, Shape.of(outputSize), Initialization.zeros());
    }

    @Override
    public Object forward(Object input) {
        Variable x = (Variable) input;
        Variable product = autograd.matmul(weight, x);
        return autograd.add(product, broadcast(bias, x.shape()));
    }

    private Variable broadcast(Variable source, Shape inputShape) {
        // weight·x has shape (out, xCols); the bias broadcasts per column
        long[] productShape = {source.shape().dimensions()[0], inputShape.rank() > 1
                ? inputShape.dimensions()[1] : 1};
        int size = (int) (productShape[0] * productShape[1]);
        double[] values = new double[size];
        java.util.Arrays.fill(values, source.data().toArray()[0]);
        return autograd.constant(values, Shape.of(productShape));
    }

    public Variable weight() {
        return weight;
    }

    public Variable bias() {
        return bias;
    }

    @Override
    public String toString() {
        return repr();
    }
}
