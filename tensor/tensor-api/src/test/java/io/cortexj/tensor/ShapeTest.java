package io.cortexj.tensor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShapeTest {

    @Test
    void ofValidatesAndKeepsDimensions() {
        Shape shape = Shape.of(2, 3, 4);
        assertArrayEquals(new long[]{2, 3, 4}, shape.dimensions());
        assertEquals(3, shape.rank());
        assertEquals(24, shape.size());
        assertThrows(IllegalArgumentException.class, () -> Shape.of());
        assertThrows(IllegalArgumentException.class, () -> Shape.of(0, 3));
    }

    @Test
    void offsetOfIsRowMajor() {
        Shape shape = Shape.of(2, 3);
        assertEquals(0, shape.offsetOf(0, 0));
        assertEquals(1, shape.offsetOf(0, 1));
        assertEquals(3, shape.offsetOf(1, 0));
        assertEquals(5, shape.offsetOf(1, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> shape.offsetOf(2, 0));
        assertThrows(IllegalArgumentException.class, () -> shape.offsetOf(1));
    }

    @Test
    void valueSemantics() {
        assertEquals(Shape.of(2, 3), Shape.of(2, 3));
        assertEquals(Shape.of(2, 3).hashCode(), Shape.of(2, 3).hashCode());
        assertFalse(Shape.of(2, 3).equals(Shape.of(3, 2)));
    }
}
