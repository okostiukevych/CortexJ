package io.cortexj.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelDomainTest {

    @Test
    void modelReferenceNormalizesAndEquals() {
        ModelReference a = ModelReference.of(" Qwen/Qwen3-8B ");
        ModelReference b = ModelReference.of("Qwen/Qwen3-8B");
        assertEquals(a, b);
        ModelReference hf = ModelReference.ofHuggingFace("Qwen/Qwen3-8B");
        assertEquals("hf:Qwen/Qwen3-8B", hf.value());
        assertThrows(IllegalArgumentException.class, () -> ModelReference.of("  "));
    }

    @Test
    void quantizationParsesBits() {
        assertEquals(4, Quantization.parse("q4_k_m").bitsPerWeight());
        assertEquals(6, Quantization.parse("Q6_K").bitsPerWeight());
        assertEquals(8, Quantization.parse("q8_0.gguf").bitsPerWeight());
        assertEquals(16, Quantization.parse("fp16").bitsPerWeight());
        assertEquals(4, Quantization.parse("4bit").bitsPerWeight());
        assertTrue(Quantization.parse("q6_k").compareTo(Quantization.parse("q4_k")) > 0);
    }

    @Test
    void artifactFormatDetectedFromFile() {
        assertEquals(ArtifactFormat.GGUF, ArtifactFormat.fromFileName("model-Q4_K.gguf"));
        assertEquals(ArtifactFormat.ONNX, ArtifactFormat.fromFileName("model.onnx"));
        assertEquals(ArtifactFormat.SAFETENSORS, ArtifactFormat.fromFileName("weights.safetensors"));
        assertEquals(ArtifactFormat.TOKENIZER, ArtifactFormat.fromFileName("tokenizer.json"));
        assertEquals(ArtifactFormat.CONFIG, ArtifactFormat.fromFileName("config.json"));
        assertEquals(ArtifactFormat.UNKNOWN, ArtifactFormat.fromFileName("weights.bin"));
        assertTrue(ArtifactFormat.GGUF.isExecutableWeights());
    }

    @Test
    void taskCapabilityMapsCommonTags() {
        assertEquals(TaskCapability.TEXT_GENERATION, TaskCapability.fromCommonTag("text-generation"));
        assertEquals(TaskCapability.EMBEDDING, TaskCapability.fromCommonTag("embeddings"));
        assertEquals(TaskCapability.EMBEDDING, TaskCapability.fromCommonTag("feature-extraction"));
    }

    @Test
    void lifecycleTransitionsAreValidated() {
        assertTrue(ModelLifecycleState.DISCOVERED.canTransitionTo(ModelLifecycleState.RESOLVING));
        assertTrue(ModelLifecycleState.LOADING.canTransitionTo(ModelLifecycleState.READY));
        assertTrue(ModelLifecycleState.READY.canTransitionTo(ModelLifecycleState.CLOSING));
        assertFalse(ModelLifecycleState.CLOSED.canTransitionTo(ModelLifecycleState.READY));
        assertFalse(ModelLifecycleState.LOADING.canTransitionTo(ModelLifecycleState.DOWNLOADING));
        assertTrue(ModelLifecycleState.FAILED.isTerminal());
    }

    @Test
    void descriptorBuilderCreatesImmutableCopies() {
        ModelDescriptor descriptor = ModelDescriptor.builder("org/model")
                .revision("rev1")
                .task(TaskCapability.TEXT_GENERATION)
                .artifact(new ModelArtifact.Abstract("a.gguf", ArtifactFormat.GGUF, 1024, Checksum.sha256("aa"), null) {
                })
                .build();
        assertEquals("org/model", descriptor.canonicalId());
        assertTrue(descriptor.supports(TaskCapability.TEXT_GENERATION));
        assertEquals(1, descriptor.artifacts().size());
        assertThrows(UnsupportedOperationException.class, () -> descriptor.tasks().add(TaskCapability.VISION));
    }
}
