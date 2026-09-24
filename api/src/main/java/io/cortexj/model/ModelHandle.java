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
}
