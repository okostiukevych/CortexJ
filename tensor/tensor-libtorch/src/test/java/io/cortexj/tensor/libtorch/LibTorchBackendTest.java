package io.cortexj.tensor.libtorch;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LibTorch backend contract without native code: construction fails
 * typed without a shim, the op allowlist is explicit, version
 * mismatches are refused.
 */
class LibTorchBackendTest {

    @Test
    void missingShimFailsTypedWithRemediation() {
        io.cortexj.error.CortexJException failure = assertThrows(
                io.cortexj.error.CortexJException.class,
                () -> new LibTorchBackend(Map.of()));
        assertEquals("CORTEXJ-TENSOR-0005", failure.code());
        assertTrue(!failure.remediation().isEmpty());
    }

    @Test
    void missingShimFileFailsTyped() {
        io.cortexj.error.CortexJException failure = assertThrows(
                io.cortexj.error.CortexJException.class,
                () -> new LibTorchBackend(Map.of(
                        LibTorchBackend.CONFIG_SHIM_PATH, "/no/such/shim.dylib")));
        assertEquals("CORTEXJ-TENSOR-0005", failure.code());
    }

    @Test
    void opAllowlistIsExplicit() {
        assertTrue(LibTorchBackend.supportedOps().contains("add"));
        assertTrue(LibTorchBackend.supportedOps().contains("mul"));
        assertTrue(LibTorchBackend.supportedOps().contains("matmul"));
        assertTrue(LibTorchBackend.supportedOps().contains("relu"));
        assertEquals(4, LibTorchBackend.supportedOps().size());
    }

    @Test
    void abiVersionIsPinned() {
        assertEquals(1, LibTorchBackend.SHIM_ABI_VERSION);
    }

    @Test
    void idIsStable() {
        // construction fails before id() matters; the constant is the contract
        assertEquals("libtorch", LibTorchBackend.BACKEND_ID);
    }
}
