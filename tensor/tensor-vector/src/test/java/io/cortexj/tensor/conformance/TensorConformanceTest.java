package io.cortexj.tensor.conformance;

import io.cortexj.tensor.DType;
import io.cortexj.tensor.Device;
import io.cortexj.tensor.Shape;
import io.cortexj.tensor.Tensor;
import io.cortexj.tensor.TensorBackend;
import io.cortexj.tensor.cpu.CpuBackend;
import io.cortexj.tensor.vector.VectorBackend;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Op-matrix conformance (ADR-025): every op family executes through
 * both backends with parity assertions. The reference is the CPU
 * scalar backend; the accelerated vector backend must match within
 * float tolerance.
 */
class TensorConformanceTest {

    private static final TensorBackend CPU = new CpuBackend();
    private static final TensorBackend VECTOR = new VectorBackend();
    private static final double TOLERANCE = 1e-4;

    private static double[] matrixA() {
        return new double[]{
                1.0, -0.5, 2.0,
                0.25, 3.0, -1.0
        };
    }

    private static double[] matrixB() {
        return new double[]{
                0.5, 1.0,
                -2.0, 0.0,
                1.5, 0.25
        };
    }

    @Test
    void matmulParityBetweenBackends() {
        Tensor a = CPU.create(matrixA(), Shape.of(2, 3), DType.F32, Device.CPU);
        Tensor b = CPU.create(matrixB(), Shape.of(3, 2), DType.F32, Device.CPU);
        Tensor reference = CPU.matmul(a, b);
        Tensor accelerated = VECTOR.matmul(a, b);
        assertEquals(reference.shape(), accelerated.shape());
        assertParity(reference.toArray(), accelerated.toArray());
        // hand-computed: [0][0] = 1*0.5 + (-0.5)*(-2) + 2*1.5 = 4.5
        assertEquals(4.5, reference.get(0, 0), TOLERANCE);
        // [0][1] = 1*1 + (-0.5)*0 + 2*0.25 = 1.5
        assertEquals(1.5, reference.get(0, 1), TOLERANCE);
        // [1][0] = 0.25*0.5 + 3*(-2) + (-1)*1.5 = -7.375
        assertEquals(-7.375, reference.get(1, 0), TOLERANCE);
        // [1][1] = 0.25*1 + 3*0 + (-1)*0.25 = 0.0
        assertEquals(0.0, reference.get(1, 1), TOLERANCE);
    }

    @Test
    void reductionsParityBetweenBackends() {
        Tensor a = CPU.create(matrixA(), Shape.of(2, 3), DType.F32, Device.CPU);
        assertEquals(4.75, CPU.sum(a).get(0), TOLERANCE);
        assertEquals(4.75, VECTOR.sum(a).get(0), TOLERANCE);
        assertEquals(4.75 / 6.0, CPU.mean(a).get(0), TOLERANCE);
        assertEquals(4.75 / 6.0, VECTOR.mean(a).get(0), TOLERANCE);
    }

    @Test
    void softmaxAndReluParity() {
        double[] values = {1.0, 2.0, 3.0, -1.0};
        Tensor a = CPU.create(values, Shape.of(4), DType.F32, Device.CPU);
        assertParity(CPU.softmax(a).toArray(), VECTOR.softmax(a).toArray());
        double sum = 0;
        for (double element : CPU.softmax(a).toArray()) {
            sum += element;
        }
        assertEquals(1.0, sum, TOLERANCE);
        assertParity(CPU.relu(a).toArray(), VECTOR.relu(a).toArray());
        assertEquals(0.0, CPU.relu(a).get(3), TOLERANCE);
    }

    @Test
    void elementwiseReshapeTransposeParity() {
        Tensor a = CPU.create(matrixA(), Shape.of(2, 3), DType.F32, Device.CPU);
        Tensor b = CPU.create(matrixB().length == 6 ? new double[]{1, 1, 1, 1, 1, 1} : matrixB(),
                Shape.of(2, 3), DType.F32, Device.CPU);
        assertParity(CPU.add(a, b).toArray(), VECTOR.add(a, b).toArray());
        assertParity(CPU.mul(a, b).toArray(), VECTOR.mul(a, b).toArray());
        Tensor reshaped = CPU.reshape(a, Shape.of(3, 2));
        assertEquals(Shape.of(3, 2), reshaped.shape());
        assertEquals(6, reshaped.shape().size());
        Tensor transposed = CPU.transpose(a);
        assertEquals(Shape.of(3, 2), transposed.shape());
        // transposed[0][1] = A[1][0] = 0.25
        assertEquals(0.25, transposed.get(0, 1), TOLERANCE);
    }

    @Test
    void ownershipLifecycleIsExplicit() {
        Tensor a = CPU.create(matrixA(), Shape.of(2, 3), DType.F32, Device.CPU);
        assertTrue(!a.isView());
        a.close();
        a.close();
        // closed tensor fails typed on use, never segfaults
        assertThrows(Exception.class, () -> a.get(0, 0));
    }

    @Test
    void deviceTransferFailsTypedNotSilently() {
        Tensor a = CPU.create(matrixA(), Shape.of(2, 3), DType.F32, Device.CPU);
        assertThrows(Exception.class, () -> a.to(Device.GPU));
    }

    @Test
    void backendsReportCapabilityHonestly() {
        assertTrue(CPU.supports(DType.F32, Device.CPU));
        assertTrue(!CPU.supports(DType.F64, Device.CPU));
        assertTrue(VECTOR.supports(DType.F32, Device.CPU));
        assertEquals("cpu", CPU.id());
        assertEquals("vector", VECTOR.id());
        assertTrue(((VectorBackend) VECTOR).vectorLength() >= 4);
    }

    @Test
    void activationsNormalizeGatherScatterParity() {
        double[] values = {1.0, 2.0, -1.0, -2.0};
        Tensor a = CPU.create(values, Shape.of(2, 2), DType.F32, Device.CPU);
        // activations
        assertParity(CPU.sigmoid(a).toArray(), VECTOR.sigmoid(a).toArray());
        assertEquals(0.5, CPU.sigmoid(CPU.create(new double[]{0.0}, Shape.of(1), DType.F32, Device.CPU)).get(0), TOLERANCE);
        assertParity(CPU.tanh(a).toArray(), VECTOR.tanh(a).toArray());
        assertEquals(0.0, CPU.tanh(CPU.create(new double[]{0.0}, Shape.of(1), DType.F32, Device.CPU)).get(0), TOLERANCE);
        assertParity(CPU.gelu(a).toArray(), VECTOR.gelu(a).toArray());
        assertEquals(0.0, CPU.gelu(CPU.create(new double[]{0.0}, Shape.of(1), DType.F32, Device.CPU)).get(0), TOLERANCE);
        // layerNorm: mean 0, variance 1 per row
        Tensor normalized = CPU.layerNorm(a, 1e-5);
        assertParity(normalized.toArray(), VECTOR.layerNorm(a, 1e-5).toArray());
        double rowMean = 0;
        for (int c = 0; c < 2; c++) {
            rowMean += normalized.get(0, c);
        }
        assertEquals(0.0, rowMean / 2, TOLERANCE);
        // gather: rows by index
        Tensor gathered = CPU.gather(a, new long[]{1, 0});
        assertEquals(Shape.of(2, 2), gathered.shape());
        // gathered[0][0] = a[1][0] = -1.0; gathered[1][0] = a[0][0] = 1.0
        assertEquals(-1.0, gathered.get(0, 0), TOLERANCE);
        assertEquals(1.0, gathered.get(1, 0), TOLERANCE);
        assertParity(CPU.gather(a, new long[]{1, 0}).toArray(),
                VECTOR.gather(a, new long[]{1, 0}).toArray());
        // scatter-add: repeated indices add
        Tensor scattered = CPU.scatter(new long[]{0, 0}, CPU.create(
                new double[]{1.0, 1.0, 2.0, 2.0}, Shape.of(2, 2), DType.F32, Device.CPU),
                Shape.of(1, 2));
        assertEquals(3.0, scattered.get(0, 0), TOLERANCE);
        assertEquals(3.0, scattered.get(0, 1), TOLERANCE);
        assertParity(scattered.toArray(), VECTOR.scatter(new long[]{0, 0}, CPU.create(
                new double[]{1.0, 1.0, 2.0, 2.0}, Shape.of(2, 2), DType.F32, Device.CPU),
                Shape.of(1, 2)).toArray());
    }

    @Test
    void gatherScatterRejectsOutOfRange() {
        Tensor a = CPU.create(matrixA(), Shape.of(2, 3), DType.F32, Device.CPU);
        assertThrows(IndexOutOfBoundsException.class, () -> CPU.gather(a, new long[]{2}));
        assertThrows(IndexOutOfBoundsException.class, () -> CPU.scatter(new long[]{5},
                CPU.create(new double[]{1, 2, 3}, Shape.of(1, 3), DType.F32, Device.CPU),
                Shape.of(2, 3)));
    }

    private static void assertParity(double[] reference, double[] accelerated) {
        assertEquals(reference.length, accelerated.length);
        for (int i = 0; i < reference.length; i++) {
            assertEquals(reference[i], accelerated[i], TOLERANCE,
                    "parity broken at " + i + ": " + reference[i] + " vs " + accelerated[i]);
        }
    }
}
