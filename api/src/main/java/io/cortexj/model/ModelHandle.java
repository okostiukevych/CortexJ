package io.cortexj.model;

import io.cortexj.inference.TextGenerationCapability;
import io.cortexj.planner.ExecutionPlan;

import java.util.Optional;
import java.util.Set;

public interface ModelHandle extends AutoCloseable {

    ModelDescriptor descriptor();

    ExecutionPlan plan();

    ModelCapabilities capabilities();

    <T> T capability(Class<T> capabilityType);

    <T> Optional<T> capabilityIfSupported(Class<T> capabilityType);

    boolean supports(TaskCapability task);

    ModelLifecycleState state();

    void waitForReady() throws InterruptedException;

    @Override
    void close();

    static TextGenerationCapability asGenerator(ModelHandle handle) {
        return handle.capability(TextGenerationCapability.class);
    }

    /**
     * Convenience shortcut (spec 12.2): delegates to the text generation capability.
     * The domain capability API remains the primary interface (spec 43).
     */
    default io.cortexj.inference.GenerationResponse generate(
            io.cortexj.inference.GenerationRequest request) {
        return capability(io.cortexj.inference.TextGenerationCapability.class).generate(request);
    }

    /**
     * Convenience shortcut for embeddings (spec 45): delegates to the embedding capability.
     */
    default io.cortexj.inference.EmbeddingResponse embed(
            io.cortexj.inference.EmbeddingRequest request) {
        return capability(io.cortexj.inference.EmbeddingCapability.class).embed(request);
    }
}
