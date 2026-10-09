package io.cortexj.tensor;

import java.util.Arrays;

/**
 * Immutable tensor shape (row-major). Shapes are fixed at creation.
 */
public final class Shape {

    private final long[] dimensions;

    private Shape(long[] dimensions) {
        this.dimensions = dimensions.clone();
    }

    public static Shape of(long... dimensions) {
        if (dimensions == null || dimensions.length == 0) {
            throw new IllegalArgumentException("shape needs at least one dimension");
        }
        for (long dimension : dimensions) {
            if (dimension < 1) {
                throw new IllegalArgumentException("shape dimensions must be positive");
            }
        }
        return new Shape(dimensions);
    }

    public long[] dimensions() {
        return dimensions.clone();
    }

    public int rank() {
        return dimensions.length;
    }

    public long size() {
        long total = 1;
        for (long dimension : dimensions) {
            total *= dimension;
        }
        return total;
    }

    /**
     * Row-major offset of the given multi-index; index length must match
     * the rank and each coordinate must sit inside its dimension.
     */
    public long offsetOf(long... index) {
        if (index.length != dimensions.length) {
            throw new IllegalArgumentException("index rank " + index.length
                    + " does not match shape rank " + dimensions.length);
        }
        long offset = 0;
        for (int i = 0; i < index.length; i++) {
            if (index[i] < 0 || index[i] >= dimensions[i]) {
                throw new IndexOutOfBoundsException("index " + i + " = " + index[i]
                        + " outside " + dimensions[i]);
            }
            offset = offset * dimensions[i] + index[i];
        }
        return offset;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Shape)) {
            return false;
        }
        return Arrays.equals(dimensions, ((Shape) other).dimensions);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(dimensions);
    }

    @Override
    public String toString() {
        return Arrays.toString(dimensions);
    }
}
