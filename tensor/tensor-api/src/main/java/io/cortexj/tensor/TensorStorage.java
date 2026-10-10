package io.cortexj.tensor;

/**
 * Owned tensor storage (ADR-024): the reference backend keeps a
 * plain array; native backends refcount native memory behind this
 * contract. Views share the parent storage and never free it.
 */
public interface TensorStorage extends AutoCloseable {

    long size();

    Layout layout();

    @Override
    void close();
}
