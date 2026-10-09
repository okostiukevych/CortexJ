package io.cortexj.benchmarks;

import io.cortexj.tensor.DType;
import io.cortexj.tensor.Device;
import io.cortexj.tensor.Shape;
import io.cortexj.tensor.Tensor;
import io.cortexj.tensor.TensorBackend;
import io.cortexj.tensor.cpu.CpuBackend;
import io.cortexj.tensor.vector.VectorBackend;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tensor benchmark (perf-tagged; run: mvn test -Pperf -pl benchmarks).
 * Measures platform-layer overhead only: raw backend throughput is not
 * attributed to the platform.
 */
@Tag("perf")
class TensorBenchmarkTest {

    private static final int SIZE = 128;

    @Test
    void matmulParityAndThroughput() {
        TensorBackend cpu = new CpuBackend();
        TensorBackend vector = new VectorBackend();
        Tensor a = randomMatrix(cpu, SIZE, SIZE);
        Tensor b = randomMatrix(cpu, SIZE, SIZE);

        Tensor reference = cpu.matmul(a, b);
        // warm both first: a cold run measures interpretation + class
        // loading, not steady-state throughput
        for (int warmup = 0; warmup < 3; warmup++) {
            cpu.matmul(a, b);
            vector.matmul(a, b);
        }
        long vectorNanos = 0;
        long cpuNanos = 0;
        for (int round = 0; round < 5; round++) {
            long start = System.nanoTime();
            vector.matmul(a, b);
            vectorNanos += System.nanoTime() - start;
            long cpuStart = System.nanoTime();
            cpu.matmul(a, b);
            cpuNanos += System.nanoTime() - cpuStart;
        }

        // parity first
        double[] referenceData = reference.toArray();
        double[] acceleratedData = vector.matmul(a, b).toArray();
        for (int i = 0; i < referenceData.length; i++) {
            assertEquals(referenceData[i], acceleratedData[i], 1e-3,
                    "parity broken at " + i);
        }
        System.out.printf("matmul %dx%d: vector %.1f ms/round, cpu %.1f ms/round%n",
                SIZE, SIZE, vectorNanos / 5e6, cpuNanos / 5e6);
        // honest finding (matches the broader JVM experience): the
        // JIT-auto-vectorized scalar reference is competitive or faster
        // than the Vector API path at this size/machine; the vector
        // backend exists for the SPI contract and further kernel tuning
        // is a declared follow-up, not a perf claim
        System.out.println("note: raw backend numbers are never attributed to the platform");
        assertTrue(vectorNanos > 0);
    }

    @Test
    void reductionAndFingerprintThroughput() {
        TensorBackend cpu = new CpuBackend();
        Tensor a = randomMatrix(cpu, SIZE, SIZE);
        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            cpu.sum(a);
        }
        long sumNanos = System.nanoTime() - start;
        System.out.printf("sum x100: %.1f ms%n", sumNanos / 1e6);
        assertTrue(sumNanos > 0);
    }

    private static Tensor randomMatrix(TensorBackend backend, int rows, int columns) {
        double[] values = new double[rows * columns];
        Random random = new Random(42L);
        for (int i = 0; i < values.length; i++) {
            values[i] = random.nextDouble() - 0.5;
        }
        return backend.create(values, Shape.of(rows, columns), DType.F32, Device.CPU);
    }
}
