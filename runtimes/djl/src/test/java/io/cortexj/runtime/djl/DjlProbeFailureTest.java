package io.cortexj.runtime.djl;

import io.cortexj.ErrorCode;
import io.cortexj.error.NativeRuntimeException;
import io.cortexj.spi.runtime.RuntimeProbeResult;
import io.cortexj.tck.RuntimeProviderContract;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DjlProbeFailureTest {

    @Test
    void probeDiscoveryFailurePreservesOriginalCause() {
        BrokenDiscoveryProvider provider = new BrokenDiscoveryProvider();
        RuntimeProbeResult result = provider.probe(RuntimeProviderContract.probeContext());
        assertFalse(result.isUsable());
        assertNotNull(result.error());
        assertTrue(result.error() instanceof NativeRuntimeException);
        assertEquals(ErrorCode.NATIVE_LIBRARY_MISSING, result.error().code());
        assertNotNull(result.error().getCause(),
                "the original engine-discovery failure must stay attached as the cause (GitHub #84)");
        assertEquals("engine-discovery-boom", result.error().getCause().getMessage());
        assertEquals("DJL engine discovery failed on this machine", result.message());
    }

    private static final class BrokenDiscoveryProvider extends DjlProvider {
        @Override
        java.util.Collection<String> discoverEngines() {
            throw new IllegalStateException("engine-discovery-boom");
        }
    }
}
