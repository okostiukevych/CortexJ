package io.cortexj.planner;

import io.cortexj.error.PlanningException;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionRequestTest {

    @Test
    void builderNormalizesRuntimeIds() {
        ExecutionRequest request = ExecutionRequest.builder(ModelReference.of("org/model"))
                .requiredRuntime("ONNX")
                .preferredRuntime(" LlamaCpp ")
                .forbiddenRuntime("JInfer")
                .preferredQuantization("Q6_K")
                .requiredFormat(ArtifactFormat.GGUF)
                .build();

        assertEquals("onnx", request.requiredRuntime());
        assertEquals("llamacpp", request.preferredRuntime());
        assertTrue(request.forbiddenRuntimes().contains("jinfer"));
        assertEquals("q6_k", request.preferredQuantization());
        assertEquals(ArtifactFormat.GGUF, request.requiredFormat());
        assertFalse(request.isFallbackEnabled());
    }

    @Test
    void fallbackEnabledWithoutRequiredRuntime() {
        ExecutionRequest request = ExecutionRequest.builder(ModelReference.of("m")).build();
        assertTrue(request.isFallbackEnabled());
        assertEquals(FallbackPolicy.LOAD_TIME_ONLY, request.fallbackPolicy());
        assertEquals(QualityProfile.BALANCED, request.qualityProfile());
        assertEquals(ReproducibilityMode.FLEXIBLE, request.reproducibilityMode());
    }

    @Test
    void runtimeOptionsAreScopedByRuntime() {
        RuntimeOptions options = RuntimeOptions.forRuntime("llamacpp")
                .put("gpuLayers", Integer.valueOf(40))
                .put("threads", Integer.valueOf(8))
                .build();
        ExecutionRequest request = ExecutionRequest.builder(ModelReference.of("m"))
                .runtimeOptions(options)
                .build();

        assertEquals(40, request.optionsFor("llamacpp").get("gpuLayers"));
        assertEquals(8, request.optionsFor("llamacpp").get("threads"));
        assertTrue(request.optionsFor("onnx").isEmpty());
    }

    @Test
    void reasonCodesCarryDefaultText() {
        assertEquals("runtime is not installed or not probeable", ReasonCode.RUNTIME_UNAVAILABLE.defaultText());
        assertFalse(ReasonCode.MEMORY_LIMIT_EXCEEDED.defaultText().isEmpty());
    }

    @Test
    void planningExceptionFormatsCodeAndRemediation() {
        PlanningException exception = new PlanningException("CORTEXJ-PLAN-4001", "no candidate", "install a runtime");
        exception.detail("model", "org/model");
        String text = exception.toString();
        assertTrue(text.contains("CORTEXJ-PLAN-4001"));
        assertTrue(text.contains("model: org/model"));
        assertTrue(text.contains("install a runtime"));
    }
}
