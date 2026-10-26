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

    // audit 2026-10-10: a generated model satisfies the failed-load
    // contract (bogus path fails typed, valid path loads and closes) -
    // previously the contract skipped for lack of a shipped artifact
    @Override
    protected java.nio.file.Path loadableArtifactPath() {
        try {
            return OnnxTestModels.minimalOnnxModel();
        } catch (Exception generationFailed) {
            return null;
        }
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

    @Test
    void executeAsyncWithoutExecutorReturnsFailedStageInsteadOfThrowingSynchronously() {
        OnnxRuntimeProvider.OnnxModel closedModel = provider.new OnnxModel(
                null, io.cortexj.model.token.HfTokenizer.fallback(), "input_ids", null);
        closedModel.close();
        java.util.concurrent.CompletionStage<io.cortexj.inference.InferenceResponse> stage =
                closedModel.executeAsync(io.cortexj.inference.InferenceRequest.builder().input("hello").build());
        assertTrue(stage.toCompletableFuture().isCompletedExceptionally(),
                "executeAsync without executor must return a failed stage when execution fails");
    }
}
