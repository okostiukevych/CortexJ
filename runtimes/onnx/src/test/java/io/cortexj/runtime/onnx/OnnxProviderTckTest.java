package io.cortexj.runtime.onnx;

import io.cortexj.spi.runtime.RuntimeProbeResult;
import io.cortexj.tck.RuntimeProviderContract;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnnxProviderTckTest extends RuntimeProviderContract {

    private final OnnxRuntimeProvider provider = new OnnxRuntimeProvider();

    @Override
    protected io.cortexj.spi.runtime.RuntimeProvider provider() {
        return provider;
    }

    @Test
    void probeReportsAvailabilityOrActionableAbsence() {
        RuntimeProbeResult result = provider.probe(probeContext());
        assertNotNull(result.health());
        if (!result.isUsable()) {
            assertNotNull(result.message());
            assertTrue(result.error() != null && !result.error().remediation().isEmpty(),
                    "unavailable ONNX probe must include remediation");
        }
    }

    @Test
    void invalidArtifactFailsWithTypeLoadError() throws Exception {
        RuntimeProbeResult probe = provider.probe(probeContext());
        org.junit.jupiter.api.Assumptions.assumeTrue(probe.isUsable(),
                "ONNX native runtime not available on this machine");
        java.nio.file.Path model = OnnxTestModels.minimalOnnxModel();
        try {
            io.cortexj.error.CortexJException failure = assertThrows(
                    io.cortexj.error.CortexJException.class,
                    () -> provider.open(new io.cortexj.spi.runtime.RuntimeOpenRequest(
                            io.cortexj.model.ModelDescriptor.builder("tck/onnx").build(),
                            null, model, io.cortexj.runtime.DeviceType.CPU,
                            java.util.Collections.<String, Object>emptyMap(),
                            java.util.Collections.<String, String>emptyMap(),
                            testEnvironment(), testHardware(false), null, null))
                            .load(new io.cortexj.spi.runtime.RuntimeModelRequest(
                                    io.cortexj.model.ModelDescriptor.builder("tck/onnx").build(),
                                    null, model, io.cortexj.runtime.DeviceType.CPU,
                                    java.util.Collections.<String, Object>emptyMap())));
            assertEquals("CORTEXJ-LOAD-8001", failure.code());
            assertNotNull(failure.getMessage());
        } finally {
            java.nio.file.Files.deleteIfExists(model);
        }
    }

    @Test
    void repeatedLoadFailuresAreStableAndTyped() throws Exception {
        RuntimeProbeResult probe = provider.probe(probeContext());
        org.junit.jupiter.api.Assumptions.assumeTrue(probe.isUsable(),
                "ONNX native runtime not available on this machine");
        for (int attempt = 0; attempt < 5; attempt++) {
            java.nio.file.Path model = OnnxTestModels.minimalOnnxModel();
            try {
                io.cortexj.error.CortexJException failure = assertThrows(
                        io.cortexj.error.CortexJException.class,
                        () -> provider.open(new io.cortexj.spi.runtime.RuntimeOpenRequest(
                                io.cortexj.model.ModelDescriptor.builder("tck/onnx").build(),
                                null, model, io.cortexj.runtime.DeviceType.CPU,
                                java.util.Collections.<String, Object>emptyMap(),
                                java.util.Collections.<String, String>emptyMap(),
                                testEnvironment(), testHardware(false), null, null))
                                .load(new io.cortexj.spi.runtime.RuntimeModelRequest(
                                        io.cortexj.model.ModelDescriptor.builder("tck/onnx").build(),
                                        null, model, io.cortexj.runtime.DeviceType.CPU,
                                        java.util.Collections.<String, Object>emptyMap())));
                assertEquals("CORTEXJ-LOAD-8001", failure.code(),
                        "every retry must fail with the same typed load error");
            } finally {
                java.nio.file.Files.deleteIfExists(model);
            }
        }
    }
}
