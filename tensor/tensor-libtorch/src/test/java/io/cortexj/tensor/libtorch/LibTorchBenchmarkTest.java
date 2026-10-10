package io.cortexj.tensor.libtorch;

import io.cortexj.tensor.DType;
import io.cortexj.tensor.Device;
import io.cortexj.tensor.Shape;
import io.cortexj.tensor.Tensor;
import io.cortexj.tensor.TensorBackend;
import io.cortexj.tensor.cpu.CpuBackend;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * LibTorch benchmark (perf-tagged; run explicitly with the shim
 * built: mvn test -Pperf -pl tensor/tensor-libtorch). Raw backend
 * numbers are never attributed to the platform.
 */
@Tag("perf")
class LibTorchBenchmarkTest {

    @Test
    void matmulThroughputVsReference() {
        String shim = System.getenv(LibTorchBackend.ENV_SHIM_PATH);
        assumeTrue(shim != null && !shim.trim().isEmpty()
                        && java.nio.file.Files.isRegularFile(java.nio.file.Paths.get(shim.trim())),
                "no libtorch shim - the benchmark lane is skipped explicitly");
        LibTorchBackend backend = new LibTorchBackend(
                Map.of(LibTorchBackend.CONFIG_SHIM_PATH, shim.trim()));
        TensorBackend cpu = new CpuBackend();
        for (int size : new int[]{128, 512}) {
            Tensor a = randomMatrix(cpu, size);
            Tensor b = randomMatrix(cpu, size);
            // warmup
            for (int i = 0; i < 2; i++) {
                cpu.matmul(a, b);
                backend.matmul(a, b);
            }
            long start = System.nanoTime();
            backend.matmul(a, b);
            long libtorchNanos = System.nanoTime() - start;
            long cpuStart = System.nanoTime();
            cpu.matmul(a, b);
            long cpuNanos = System.nanoTime() - cpuStart;
            System.out.printf("matmul %dx%d: libtorch %.1f ms, cpu %.1f ms%n",
                    size, size, libtorchNanos / 1e6, cpuNanos / 1e6);
            assertTrue(libtorchNanos > 0);
        }
    }

    private static Tensor randomMatrix(TensorBackend backend, int size) {
        double[] values = new double[size * size];
        Random random = new Random(42L);
        for (int i = 0; i < values.length; i++) {
            values[i] = random.nextDouble() - 0.5;
        }
        return backend.create(values, Shape.of(size, size), DType.F32, Device.CPU);
    }
}
