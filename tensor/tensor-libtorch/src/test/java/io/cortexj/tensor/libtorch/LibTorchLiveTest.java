package io.cortexj.tensor.libtorch;

import io.cortexj.tensor.DType;
import io.cortexj.tensor.Device;
import io.cortexj.tensor.Shape;
import io.cortexj.tensor.Tensor;
import io.cortexj.tensor.TensorBackend;
import io.cortexj.tensor.cpu.CpuBackend;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live lane (issue #181): op parity against the CPU reference through
 * the real shim + libtorch. Skips loudly without
 * CORTEXJ_LIBTORCH_SHIM (default build).
 */
class LibTorchLiveTest {

    private static LibTorchBackend liveBackend() {
        String shim = System.getenv(LibTorchBackend.ENV_SHIM_PATH);
        assumeTrue(shim != null && !shim.trim().isEmpty()
                        && java.nio.file.Files.isRegularFile(java.nio.file.Paths.get(shim.trim())),
                "no libtorch shim on this machine - the live lane is skipped "
                        + "explicitly (never silently)");
        return new LibTorchBackend(Map.of(LibTorchBackend.CONFIG_SHIM_PATH, shim.trim()));
    }

    @Test
    void addMulMatmulReluMatchReference() {
        LibTorchBackend backend = liveBackend();
        TensorBackend reference = new CpuBackend();
        Tensor a = reference.create(new double[]{1, 2, 3, 4},
                Shape.of(2, 2), DType.F32, Device.CPU);
        Tensor b = reference.create(new double[]{5, 6, 7, 8},
                Shape.of(2, 2), DType.F32, Device.CPU);
        assertParity(reference.add(a, b).toArray(), backend.add(a, b).toArray());
        assertParity(reference.mul(a, b).toArray(), backend.mul(a, b).toArray());
        assertParity(reference.matmul(a, b).toArray(), backend.matmul(a, b).toArray());
        Tensor negative = reference.create(new double[]{-1, 2, -3, 4},
                Shape.of(2, 2), DType.F32, Device.CPU);
        assertParity(reference.relu(negative).toArray(), backend.relu(negative).toArray());
    }

    private static void assertParity(double[] reference, double[] actual) {
        assertEquals(reference.length, actual.length);
        for (int i = 0; i < reference.length; i++) {
            assertEquals(reference[i], actual[i], 1e-4,
                    "libtorch parity broken at " + i);
        }
    }
}
