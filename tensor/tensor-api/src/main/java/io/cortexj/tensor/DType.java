package io.cortexj.tensor;

/**
 * Supported element types. The reference suite covers F32 first;
 * other dtypes arrive with the backends that need them.
 */
public enum DType {
    F32(4),
    F64(8);

    private final int bytesPerElement;

    DType(int bytesPerElement) {
        this.bytesPerElement = bytesPerElement;
    }

    public int bytesPerElement() {
        return bytesPerElement;
    }
}
