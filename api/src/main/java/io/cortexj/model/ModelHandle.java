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

    /**
     * Blocks until the handle reaches a usable state (READY or DEGRADED) or
     * throws on a terminal one (CLOSED/FAILED). {@code Models.load()} is
     * synchronous: a handle returned by it is already READY when load()
     * returns, so this method returns immediately for it. The blocking
     * contract is real for any future asynchronous load path.
     */
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
