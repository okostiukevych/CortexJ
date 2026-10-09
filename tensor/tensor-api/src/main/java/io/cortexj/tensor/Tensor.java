package io.cortexj.tensor;

/**
 * One tensor value (ADR-024): owns its storage unless it is a view.
 * Metadata (shape/dtype/device) is immutable; close() is idempotent
 * and a closed tensor fails typed on use.
 */
public interface Tensor extends AutoCloseable {

    Shape shape();

    DType dtype();

    Device device();

    /**
     * Reads the element at the given multi-index (row-major).
     */
    double get(long... index);

    /**
     * Flat row-major view of the values (a copy for native backends).
     */
    double[] toArray();

    /**
     * True when this tensor shares storage with a parent (a view).
     */
    boolean isView();

    /**
     * Explicit device transfer; unsupported combinations fail typed,
     * never silently degrade to CPU.
     */
    Tensor to(Device device);

    @Override
    void close();
}
