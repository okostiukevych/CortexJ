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

    Tensor relu(Tensor tensor);

    Tensor reshape(Tensor tensor, Shape shape);

    Tensor transpose(Tensor tensor);
}
