package io.cortexj.core;

import io.cortexj.error.CortexJException;
import io.cortexj.error.InferenceException;
import io.cortexj.inference.InferenceRequest;
import io.cortexj.inference.InferenceResponse;
import io.cortexj.model.ModelCapabilities;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelLifecycleState;
import io.cortexj.model.TaskCapability;
import io.cortexj.planner.ExecutionCandidate;
import io.cortexj.planner.ExecutionPlan;
import io.cortexj.planner.PlanReason;
import io.cortexj.planner.ReasonCode;
import io.cortexj.spi.runtime.ModelRuntime;
import io.cortexj.spi.runtime.RuntimeModel;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.locks.ReentrantLock;

public final class DefaultModelHandle implements ModelHandle {

    private final ModelDescriptor descriptor;
    private final ExecutionPlan plan;
    private final RuntimeModel model;
    private final ModelRuntime runtime;
    private final DefaultCortexJ platform;
    private final ExecutionCandidate candidate;
    private final ReentrantLock sessionLock = new ReentrantLock();
    private final boolean serializeAccess;

    private volatile ModelLifecycleState state = ModelLifecycleState.LOADING;
    private volatile String fallbackNote;

    DefaultModelHandle(ModelDescriptor descriptor,
                       ExecutionPlan plan,
                       RuntimeModel model,
                       ModelRuntime runtime,
                       DefaultCortexJ platform,
                       ExecutionCandidate candidate) {
        this.descriptor = descriptor;
        this.plan = plan;
        this.model = model;
        this.runtime = runtime;
        this.platform = platform;
        this.candidate = candidate;
        this.serializeAccess = runtime != null
                && runtimeSupportDescriptor() == io.cortexj.runtime.ThreadSafety.SESSION_SCOPED;
        this.state = ModelLifecycleState.READY;
    }

    private io.cortexj.runtime.ThreadSafety runtimeSupportDescriptor() {
        for (io.cortexj.runtime.RuntimeDescriptor descriptor : platform.runtimes()) {
            if (descriptor.id().value().equals(candidate.runtimeId())) {
                return descriptor.threadSafety();
            }
        }
        return io.cortexj.runtime.ThreadSafety.SESSION_SCOPED;
    }

    void markFallback(String fromRuntime, String toRuntime) {
        this.fallbackNote = "load fell back from " + fromRuntime + " to " + toRuntime;
    }

    @Override
    public ModelDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ExecutionPlan plan() {
        return plan;
    }

    public String fallbackNote() {
        return fallbackNote;
    }

    @Override
    public ModelCapabilities capabilities() {
        return model.capabilities();
    }

    @Override
    public <T> T capability(Class<T> capabilityType) {
        T capability = capabilityIfSupportedInternal(capabilityType);
        if (capability == null) {
            CortexJException failure = new InferenceException("CORTEXJ-INFER-9004",
                    "Runtime '" + candidate.runtimeId() + "' does not provide capability "
                            + capabilityType.getSimpleName() + " for this model.",
                    "Pick a different runtime via ExecutionRequest.requiredRuntime.");
            failure.detail("runtime", candidate.runtimeId());
            failure.detail("capability", capabilityType.getName());
            throw failure;
        }
        return capability;
    }

    @Override
    public <T> Optional<T> capabilityIfSupported(Class<T> capabilityType) {
        return Optional.ofNullable(capabilityIfSupportedInternal(capabilityType));
    }

    @SuppressWarnings("unchecked")
    private <T> T capabilityIfSupportedInternal(Class<T> capabilityType) {
        if (capabilityType.isInstance(model)) {
            return capabilityType.cast(model);
        }
        return null;
    }

    @Override
    public boolean supports(TaskCapability task) {
        return model.capabilities().supports(task);
    }

    @Override
    public ModelLifecycleState state() {
        return state;
    }

    @Override
    public void waitForReady() throws InterruptedException {
        if (state.isTerminal()) {
            throw new IllegalStateException("Model handle is already " + state);
        }
    }

    public InferenceResponse execute(InferenceRequest request) {
        if (serializeAccess) {
            sessionLock.lock();
            try {
                return model.execute(request);
            } finally {
                sessionLock.unlock();
            }
        }
        return model.execute(request);
    }

    public CompletionStage<InferenceResponse> executeAsync(final InferenceRequest request) {
        if (serializeAccess) {
            return CompletableFuture.supplyAsync(new java.util.function.Supplier<InferenceResponse>() {
                @Override
                public InferenceResponse get() {
                    return execute(request);
                }
            }, platform.executor());
        }
        return model.executeAsync(request);
    }

    private void transitionTo(ModelLifecycleState next) {
        if (!state.canTransitionTo(next)) {
            throw new IllegalStateException(
                    "Illegal model lifecycle transition " + state + " -> " + next);
        }
        state = next;
    }

    @Override
    public void close() {
        if (state == ModelLifecycleState.CLOSED) {
            return;
        }
        transitionTo(ModelLifecycleState.CLOSING);
        try {
            model.close();
        } catch (RuntimeException ignored) {
        }
        try {
            runtime.close();
        } catch (RuntimeException ignored) {
        }
        transitionTo(ModelLifecycleState.CLOSED);
        platform.events().info(io.cortexj.observability.PlatformEvent.Type.PLATFORM_CLOSED,
                "model handle closed: " + descriptor.canonicalId());
    }
}
