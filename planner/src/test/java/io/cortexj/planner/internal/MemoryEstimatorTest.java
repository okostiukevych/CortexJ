package io.cortexj.planner.internal;

import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.planner.MemoryConfidence;
import io.cortexj.planner.MemoryEstimate;
import io.cortexj.planner.test.TestRuntimeProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dedicated unit tests for MemoryEstimator (issue #243). The estimator was
 * previously covered only indirectly through plan-level tests; these pin its
 * per-format component contracts, degenerate inputs and determinism.
 */
class MemoryEstimatorTest {

    private static final long MIB = 1024L * 1024L;

    private final MemoryEstimator estimator = new MemoryEstimator();

    @Test
    void nullArtifactIsUnknown() {
        MemoryEstimate estimate = estimator.estimate(null, 4096);
        assertFalse(estimate.isKnown(), "null artifact must produce an unknown estimate");
        assertTrue(estimate.components().isEmpty());
        assertEquals(-1L, estimate.totalBytes(),
                "unknown estimates carry the -1 sentinel total (documented contract)");
    }

    @Test
    void remoteArtifactCostsOnlyClientBuffers() {
        ModelArtifact artifact = TestRuntimeProvider.artifact(
                "gpt.model.remote", ArtifactFormat.REMOTE, 0L, "none");
        MemoryEstimate estimate = estimator.estimate(artifact, 4096);
        assertTrue(estimate.isKnown());
        assertEquals(16L * MIB, estimate.totalBytes());
        assertEquals(16L * MIB, estimate.components().get("clientBuffers").longValue());
        assertEquals(MemoryConfidence.HIGH, estimate.confidence());
        assertEquals(0L, estimate.safetyMarginBytes());
    }

    @Test
    void ggufComponentsFollowTheDocumentedFormula() {
        long weights = 4_000L * MIB;
        ModelArtifact artifact = TestRuntimeProvider.artifact(
                "qwen.gguf", ArtifactFormat.GGUF, weights, "q4");
        MemoryEstimate estimate = estimator.estimate(artifact, 2048);

        long kvCache = estimate.components().get("kvCache").longValue();
        long overhead = estimate.components().get("runtimeOverhead").longValue();
        long margin = estimate.components().get("safetyMargin").longValue();

        assertEquals(weights, estimate.components().get("weights").longValue());
        assertEquals(256L * MIB, overhead);
        assertEquals((long) ((weights + kvCache) * 0.15d), margin);
        assertEquals(weights + kvCache + overhead + margin, estimate.totalBytes());
        assertEquals(MemoryConfidence.MEDIUM, estimate.confidence());
        assertEquals(margin, estimate.safetyMarginBytes());
        assertTrue(estimate.totalBytes() > weights, "the estimate must exceed weights");

        // kv cache scales linearly with the context hint
        long kvCacheBigger = estimator.estimate(artifact, 4096)
                .components().get("kvCache").longValue();
        assertTrue(kvCacheBigger > kvCache,
                "a larger context must cost more kv cache: " + kvCache + " vs " + kvCacheBigger);
    }

    @Test
    void nonPositiveContextHintFallsBackToTheDefault() {
        long weights = 1_000L * MIB;
        ModelArtifact artifact = TestRuntimeProvider.artifact(
                "qwen.gguf", ArtifactFormat.GGUF, weights, "q4");
        long kvZero = estimator.estimate(artifact, 0).components().get("kvCache").longValue();
        long kvNegative = estimator.estimate(artifact, -7).components().get("kvCache").longValue();
        long kvDefault = estimator.estimate(artifact, 4096).components().get("kvCache").longValue();
        assertEquals(kvDefault, kvZero, "context 0 must fall back to the default");
        assertEquals(kvDefault, kvNegative, "negative context must fall back to the default");
    }

    @Test
    void onnxComponentsFollowTheDocumentedFormula() {
        long modelSize = 500L * MIB;
        ModelArtifact artifact = TestRuntimeProvider.artifact(
                "model.onnx", ArtifactFormat.ONNX, modelSize, "int8");
        MemoryEstimate estimate = estimator.estimate(artifact, 4096);

        long activations = (long) (modelSize * 0.25d);
        long margin = (long) ((modelSize + activations) * 0.15d);
        assertEquals(modelSize, estimate.components().get("modelSize").longValue());
        assertEquals(activations, estimate.components().get("activationEstimate").longValue());
        assertEquals(128L * MIB, estimate.components().get("runtimeOverhead").longValue());
        assertEquals(margin, estimate.components().get("safetyMargin").longValue());
        assertEquals(modelSize + activations + 128L * MIB + margin, estimate.totalBytes());
        assertEquals(MemoryConfidence.MEDIUM, estimate.confidence());
    }

    @Test
    void safetensorsUsesTheWidestMarginAndLowConfidence() {
        long weights = 1_000L * MIB;
        ModelArtifact artifact = TestRuntimeProvider.artifact(
                "model.safetensors", ArtifactFormat.SAFETENSORS, weights, "fp16");
        MemoryEstimate estimate = estimator.estimate(artifact, 4096);

        long kvCache = estimate.components().get("kvCache").longValue();
        long margin = (long) ((weights + kvCache) * 0.35d);
        assertEquals(384L * MIB, estimate.components().get("runtimeOverhead").longValue());
        assertEquals(margin, estimate.components().get("safetyMargin").longValue());
        assertEquals(weights + kvCache + 384L * MIB + margin, estimate.totalBytes());
        assertEquals(MemoryConfidence.LOW, estimate.confidence(),
                "safetensors without a known runtime layout is the least confident path");
    }

    @Test
    void unknownFormatIsBoundedAndNeverNaN() {
        ModelArtifact artifact = TestRuntimeProvider.artifact(
                "thing.tokenizer.json", ArtifactFormat.TOKENIZER, 10L * MIB, "none");
        MemoryEstimate estimate = estimator.estimate(artifact, 4096);
        assertTrue(estimate.isKnown());
        assertEquals((long) (10L * MIB * 1.5d) + 128L * MIB, estimate.totalBytes());
        assertEquals(10L * MIB, estimate.components().get("artifact").longValue());
        assertEquals(5L * MIB, estimate.safetyMarginBytes());
        assertEquals(MemoryConfidence.LOW, estimate.confidence());
    }

    @Test
    void unknownFormatWithoutSizeIsUnknown() {
        ModelArtifact artifact = TestRuntimeProvider.artifact(
                "thing.unknown", ArtifactFormat.UNKNOWN, 0L, "none");
        assertFalse(estimator.estimate(artifact, 4096).isKnown());
    }

    @Test
    void estimationIsDeterministic() {
        ModelArtifact artifact = TestRuntimeProvider.artifact(
                "qwen.gguf", ArtifactFormat.GGUF, 2_000L * MIB, "q4");
        MemoryEstimate first = estimator.estimate(artifact, 4096);
        MemoryEstimate second = estimator.estimate(artifact, 4096);
        assertEquals(first.totalBytes(), second.totalBytes());
        assertEquals(first.components(), second.components());
    }
}
