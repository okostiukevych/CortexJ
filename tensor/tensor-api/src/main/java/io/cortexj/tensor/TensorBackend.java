package io.cortexj.tensor;

/**
 * SPI for tensor engines (ADR-025). A backend reports capability and
 * creates tensors; the operation families are the shared contract.
 */
public interface TensorBackend {

    String id();

    boolean supports(DType dtype, Device device);

    /**
     * Creates a tensor from flat row-major values. The value count must
     * match the shape size; a mismatch fails typed.
     */
    Tensor create(double[] values, Shape shape, DType dtype, Device device);

    /**
     * Core math ops executed by the backend. Backends must produce
     * parity-equal results for the same op (the conformance suite
     * compares reference and accelerated backends).
     */
    Tensor matmul(Tensor left, Tensor right);

    Tensor add(Tensor left, Tensor right);

    Tensor mul(Tensor left, Tensor right);

    Tensor sum(Tensor tensor);

    Tensor mean(Tensor tensor);

    Tensor softmax(Tensor tensor);

    Tensor sigmoid(Tensor tensor);

    Tensor tanh(Tensor tensor);

    Tensor gelu(Tensor tensor);

    /**
     * Layer normalization over the last dimension: (x - mean) /
     * sqrt(variance + epsilon), scaled and shifted if gamma/beta given.
     */
    Tensor layerNorm(Tensor tensor, double epsilon);

    /**
     * Gathers rows from a 2D source by the given indices.
     */
    Tensor gather(Tensor source, long[] indices);

    /**
     * Scatters values into a 2D target at the given row indices
     * (target starts zeroed; repeated indices add).
     */
    Tensor scatter(long[] indices, Tensor values, Shape targetShape);

    Tensor relu(Tensor tensor);

    Tensor reshape(Tensor tensor, Shape shape);

    Tensor transpose(Tensor tensor);
}
