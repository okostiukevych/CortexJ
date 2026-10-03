package io.cortexj.runtime.onnx;

import ai.onnxruntime.OrtEnvironment;
import io.cortexj.ErrorCode;
import io.cortexj.error.RuntimeUnavailableException;
import io.cortexj.spi.runtime.RuntimeProbeResult;
import io.cortexj.tck.RuntimeProviderContract;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnnxProbeFailureTest {

    @Test
    void probeFailurePreservesOriginalCause() {
        BrokenEnvironmentProvider provider = new BrokenEnvironmentProvider();
        RuntimeProbeResult result = provider.probe(RuntimeProviderContract.probeContext());
        assertFalse(result.isUsable());
        assertNotNull(result.error());
        assertTrue(result.error() instanceof RuntimeUnavailableException);
        assertEquals(ErrorCode.RUNTIME_NOT_INSTALLED, result.error().code());
        assertNotNull(result.error().getCause(),
                "the original native-load failure must stay attached as the cause (GitHub #84)");
        assertEquals("native-load-boom", result.error().getCause().getMessage());
        assertEquals("ONNX Runtime native library could not be loaded", result.message());
    }

    private static final class BrokenEnvironmentProvider extends OnnxRuntimeProvider {
        @Override
        OrtEnvironment environment() {
            throw new IllegalStateException("native-load-boom");
        }
    }
}
