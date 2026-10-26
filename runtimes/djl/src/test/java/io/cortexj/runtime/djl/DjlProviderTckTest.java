package io.cortexj.runtime.djl;

import io.cortexj.error.NativeRuntimeException;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.spi.runtime.RuntimeEvaluationRequest;
import io.cortexj.spi.runtime.RuntimeProbeResult;
import io.cortexj.tck.RuntimeProviderContract;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DjlProviderTckTest extends RuntimeProviderContract {

    private final DjlProvider provider = new DjlProvider();

    @Override
    protected io.cortexj.spi.runtime.RuntimeProvider provider() {
        return provider;
    }

    @Test
    void probeWithoutEngineArtifactsIsUnavailableWithRemediation() {
        RuntimeProbeResult result = provider.probe(probeContext());
        if (!result.isUsable()) {
            assertEquals(io.cortexj.runtime.RuntimeHealth.UNAVAILABLE, result.health());
            assertNotNull(result.error());
            assertEquals("CORTEXJ-NATIVE-6001", result.error().code());
            assertTrue(!result.error().remediation().isEmpty());
            String remediation = result.error().remediation().get(0);
            assertTrue(remediation.contains("onnxruntime-engine"),
                    "remediation must name the DJL engine artifact");
        }
    }

    private RuntimeEvaluationRequest evaluation(io.cortexj.model.ArtifactFormat format) {
        return new RuntimeEvaluationRequest(
                io.cortexj.model.ModelDescriptor.builder("tck/djl").build(),
                new io.cortexj.model.ModelArtifact.Abstract(
                        "model." + format.name().toLowerCase(), format, 1_000_000L, null, null) {
                },
                testEnvironment(), testHardware(false), null);
    }

    @Test
    void evaluateAcceptsOnnxOnCpu() {
        assertTrue(provider.evaluate(evaluation(ArtifactFormat.ONNX)).isSupported());
    }

    @Test
    void evaluateRejectsNonOnnxArtifacts() {
        assertFalse(provider.evaluate(evaluation(ArtifactFormat.GGUF)).isSupported());
    }

    @Test
    void openWithoutEngineThrowsActionableNativeError() throws Exception {
        Path onnx = Files.createTempFile("model", ".onnx");
        try {
            NativeRuntimeException expected = assertThrows(NativeRuntimeException.class,
                    () -> provider.open(new io.cortexj.spi.runtime.RuntimeOpenRequest(
                            io.cortexj.model.ModelDescriptor.builder("tck/djl").build(),
                            null, onnx, io.cortexj.runtime.DeviceType.CPU,
                            java.util.Collections.<String, Object>emptyMap(),
                            java.util.Collections.<String, String>emptyMap(),
                            testEnvironment(), testHardware(false), null, null)));
            assertEquals("CORTEXJ-NATIVE-6001", expected.code());
            assertTrue(!expected.remediation().isEmpty());
            assertTrue(expected.remediation().get(0).contains("onnxruntime-engine"),
                    "remediation must tell how to install the DJL engine");
        } finally {
            Files.deleteIfExists(onnx);
        }
    }

    @Test
    void executeAsyncReturnsFailedStageWithoutExecutorAndOnRejectedExecution() {
        final java.util.concurrent.atomic.AtomicBoolean modelClosed =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        ai.djl.Model stubModel = (ai.djl.Model) java.lang.reflect.Proxy.newProxyInstance(
                ai.djl.Model.class.getClassLoader(),
                new Class<?>[]{ai.djl.Model.class},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                        if ("close".equals(method.getName())) {
                            modelClosed.set(true);
                        }
                        return null;
                    }
                });
        DjlProvider.DjlModel noExecModel = provider.new DjlModel(
                stubModel, io.cortexj.model.token.HfTokenizer.fallback(), null);
        noExecModel.close();
        assertTrue(modelClosed.get());

        java.util.concurrent.CompletionStage<io.cortexj.inference.InferenceResponse> failedStage =
                noExecModel.executeAsync(io.cortexj.inference.InferenceRequest.builder().input("hello").build());
        assertTrue(failedStage.toCompletableFuture().isCompletedExceptionally(),
                "executeAsync without executor must return a failed stage instead of throwing synchronously");

        java.util.concurrent.ExecutorService shutdownExec =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        shutdownExec.shutdownNow();
        DjlProvider.DjlModel rejectedModel = provider.new DjlModel(
                stubModel, io.cortexj.model.token.HfTokenizer.fallback(), shutdownExec);
        java.util.concurrent.CompletionStage<io.cortexj.inference.InferenceResponse> rejectedStage =
                rejectedModel.executeAsync(io.cortexj.inference.InferenceRequest.builder().input("hello").build());
        assertTrue(rejectedStage.toCompletableFuture().isCompletedExceptionally(),
                "executeAsync on rejected execution must return a failed stage");
    }
}
