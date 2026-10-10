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

    /**
     * Bounded variant of {@link #waitForReady()} (audit 2026-10-08, #153):
     * returns {@code true} when READY/DEGRADED is reached in time,
     * {@code false} on timeout, and throws on a terminal state like the
     * unbounded version. A default polling implementation is provided so
     * existing implementers keep compiling; implementations with a state
     * monitor should override it.
     */
    default boolean waitForReady(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMillis);
        for (;;) {
            ModelLifecycleState current = state();
            if (current == ModelLifecycleState.READY || current == ModelLifecycleState.DEGRADED) {
                return true;
            }
            if (current.isTerminal()) {
                throw new IllegalStateException("Model handle is already " + current);
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0L) {
                return false;
            }
            Thread.sleep(Math.min(50L, remaining));
        }
    }

    @Override
    void close();

    static TextGenerationCapability asGenerator(ModelHandle handle) {
        return handle.capability(TextGenerationCapability.class);
    }

    /**
     * Convenience shortcut: delegates to the text generation capability.
     * The domain capability API remains the primary interface.
     */
    default io.cortexj.inference.GenerationResponse generate(
            io.cortexj.inference.GenerationRequest request) {
        return capability(io.cortexj.inference.TextGenerationCapability.class).generate(request);
    }

    /**
     * Convenience shortcut for embeddings: delegates to the embedding capability.
     */
    default io.cortexj.inference.EmbeddingResponse embed(
            io.cortexj.inference.EmbeddingRequest request) {
        return capability(io.cortexj.inference.EmbeddingCapability.class).embed(request);
    }
}
