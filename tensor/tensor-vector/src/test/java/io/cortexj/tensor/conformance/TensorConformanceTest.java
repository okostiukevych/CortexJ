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

    @Test
    void broadcastingFollowsNumpySemantics() {
        Tensor matrix = CPU.create(new double[]{1, 2, 3, 4}, Shape.of(2, 2), DType.F32, Device.CPU);
        Tensor row = CPU.create(new double[]{10, 20}, Shape.of(2), DType.F32, Device.CPU);
        Tensor sum = CPU.add(matrix, row);
        assertEquals(Shape.of(2, 2), sum.shape());
        assertEquals(11.0, sum.get(0, 0), TOLERANCE);
        assertEquals(22.0, sum.get(0, 1), TOLERANCE);
        assertEquals(13.0, sum.get(1, 0), TOLERANCE);
        assertEquals(24.0, sum.get(1, 1), TOLERANCE);
        // scalar-shaped broadcast
        Tensor one = CPU.create(new double[]{5}, Shape.of(1), DType.F32, Device.CPU);
        assertEquals(6.0, CPU.add(matrix, one).get(0, 0), TOLERANCE);
        // incompatible shapes fail typed
        Tensor bad = CPU.create(new double[]{1, 2, 3}, Shape.of(3), DType.F32, Device.CPU);
        assertThrows(IllegalArgumentException.class, () -> CPU.add(matrix, bad));
    }

    @Test
    void creationFamilyIsSeeded() {
        Tensor zeros = CPU.zeros(Shape.of(2, 2), DType.F32, Device.CPU);
        assertEquals(0.0, zeros.get(1, 1), TOLERANCE);
        Tensor filled = CPU.fill(Shape.of(3), 2.5, DType.F32, Device.CPU);
        assertEquals(2.5, filled.get(2), TOLERANCE);
        Tensor first = CPU.random(Shape.of(4), DType.F32, Device.CPU, 42L);
        Tensor second = CPU.random(Shape.of(4), DType.F32, Device.CPU, 42L);
        assertParity(first.toArray(), second.toArray());
        Tensor third = CPU.random(Shape.of(4), DType.F32, Device.CPU, 43L);
        assertTrue(first.get(0) != third.get(0));
    }

    @Test
    void batchMatmulParity() {
        double[] left = {1, 0, 0, 1, 2, 2, 2, 2};
        double[] right = {1, 2, 3, 4, 0, 1, 1, 0};
        Tensor a = CPU.create(left, Shape.of(2, 2, 2), DType.F32, Device.CPU);
        Tensor b = CPU.create(right, Shape.of(2, 2, 2), DType.F32, Device.CPU);
        Tensor reference = CPU.batchMatmul(a, b);
        assertEquals(Shape.of(2, 2, 2), reference.shape());
        // batch 0 is the identity: result equals b[0]
        assertEquals(1.0, reference.get(0, 0, 0), TOLERANCE);
        assertEquals(4.0, reference.get(0, 1, 1), TOLERANCE);
        // batch 1: [[2,2],[2,2]] x [[0,1],[1,0]] = [[2,2],[2,2]]
        assertEquals(2.0, reference.get(1, 0, 0), TOLERANCE);
        assertEquals(2.0, reference.get(1, 1, 1), TOLERANCE);
        assertParity(reference.toArray(), VECTOR.batchMatmul(a, b).toArray());
    }

    @Test
    void logSoftmaxSumsToOneInExpSpace() {
        Tensor a = CPU.create(new double[]{1.0, 2.0, 3.0}, Shape.of(3), DType.F32, Device.CPU);
        Tensor logged = CPU.logSoftmax(a);
        double total = 0;
        for (double value : logged.toArray()) {
            total += Math.exp(value);
            assertTrue(value <= 0, "log-probabilities are non-positive");
        }
        assertEquals(1.0, total, TOLERANCE);
        assertParity(logged.toArray(), VECTOR.logSoftmax(a).toArray());

        // 2D multi-row softmax and logSoftmax normalize each row independently
        Tensor batched = CPU.create(new double[]{1.0, 2.0, 3.0, -2.0, 0.0, 2.0}, Shape.of(2, 3), DType.F32, Device.CPU);
        Tensor sm = CPU.softmax(batched);
        assertEquals(1.0, sm.get(0, 0) + sm.get(0, 1) + sm.get(0, 2), TOLERANCE);
        assertEquals(1.0, sm.get(1, 0) + sm.get(1, 1) + sm.get(1, 2), TOLERANCE);
        assertParity(sm.toArray(), VECTOR.softmax(batched).toArray());

        Tensor lsm = CPU.logSoftmax(batched);
        assertEquals(1.0, Math.exp(lsm.get(0, 0)) + Math.exp(lsm.get(0, 1)) + Math.exp(lsm.get(0, 2)), TOLERANCE);
        assertEquals(1.0, Math.exp(lsm.get(1, 0)) + Math.exp(lsm.get(1, 1)) + Math.exp(lsm.get(1, 2)), TOLERANCE);
        assertParity(lsm.toArray(), VECTOR.logSoftmax(batched).toArray());
    }

    @Test
    void sliceIsAViewSharingStorage() {
        Tensor a = CPU.create(new double[]{1, 2, 3, 4, 5, 6}, Shape.of(3, 2), DType.F32, Device.CPU);
        Tensor view = CPU.slice(a, 1, 3);
        assertTrue(view.isView());
        assertEquals(Shape.of(2, 2), view.shape());
        assertEquals(3.0, view.get(0, 0), TOLERANCE);
        assertEquals(6.0, view.get(1, 1), TOLERANCE);
        // Mutating the slice via setAll updates the underlying tensor a
        view.setAll(new double[]{30, 40, 50, 60});
        assertEquals(30.0, a.get(1, 0), TOLERANCE);
        assertEquals(60.0, a.get(2, 1), TOLERANCE);
        // Mutating the parent tensor updates the slice view
        a.setAll(new double[]{1, 2, 300, 400, 500, 600});
        assertEquals(300.0, view.get(0, 0), TOLERANCE);
        assertEquals(600.0, view.get(1, 1), TOLERANCE);
        assertThrows(IllegalArgumentException.class, () -> CPU.slice(a, 2, 2));
        assertThrows(IllegalArgumentException.class, () -> CPU.slice(a, 0, 4));
    }

    @Test
    void unwrapIsDocumentedEscapeHatch() {
        Tensor a = CPU.create(new double[]{1, 2}, Shape.of(2), DType.F32, Device.CPU);
        Object handle = CPU.unwrap(a);
        assertTrue(handle instanceof double[]);
        assertEquals(2, ((double[]) handle).length);
        Object vectorHandle = VECTOR.unwrap(a);
        assertTrue(vectorHandle instanceof double[]);
    }

    private static void assertParity(double[] reference, double[] accelerated) {
        assertEquals(reference.length, accelerated.length);
        for (int i = 0; i < reference.length; i++) {
            assertEquals(reference[i], accelerated[i], TOLERANCE,
                    "parity broken at " + i + ": " + reference[i] + " vs " + accelerated[i]);
        }
    }
}
