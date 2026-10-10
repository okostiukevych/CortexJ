package io.cortexj.tensor;

/**
 * SPI for tensor engines (ADR-025). A backend reports capability and
 * creates tensors; the operation families are the shared contract.
 *
 * <p>Evaluation is eager: every op executes immediately and returns an
 * owning result tensor (or a view for {@link #slice}). There is no lazy
 * graph, no deferred execution, so lazy/eager behavior is never
 * ambiguous.</p>
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
     * Creation family: zeros, filled and seeded-random tensors.
     */
    Tensor zeros(Shape shape, DType dtype, Device device);

    Tensor fill(Shape shape, double value, DType dtype, Device device);

    Tensor random(Shape shape, DType dtype, Device device, long seed);

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

    /**
     * Batched matmul: [batch, m, k] x [batch, k, n] -> [batch, m, n].
     * Both inputs must be rank 3 with matching batch and inner sizes.
     */
    Tensor batchMatmul(Tensor left, Tensor right);

    Tensor logSoftmax(Tensor tensor);

    /**
     * Row slice [from, to) of a 2D tensor: a VIEW sharing the parent
     * storage (closing the view never frees shared data).
     */
    Tensor slice(Tensor tensor, long from, long to);

    /**
     * Backend-specific escape hatch, isolated: returns the native handle
     * (the storage array for the reference backend). Never part of the
     * portable contract; backends document what they return.
     */
    Object unwrap(Tensor tensor);

}
