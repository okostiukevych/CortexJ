package io.cortexj.core;

import io.cortexj.ErrorCode;
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

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(DefaultModelHandle.class.getName());

    private final ModelDescriptor descriptor;
    private final ExecutionPlan plan;
    private volatile RuntimeModel model;
    private volatile ModelRuntime runtime;
    private final DefaultCortexJ platform;
    private final ExecutionCandidate candidate;
    private final ReentrantLock sessionLock = new ReentrantLock();
    private final boolean serializeAccess;
    private volatile java.util.function.Consumer<DefaultModelHandle> closeCallback;
    private final Object lifecycleMonitor = new Object();

    private final java.util.concurrent.atomic.AtomicReference<ModelLifecycleState> state =
            new java.util.concurrent.atomic.AtomicReference<ModelLifecycleState>(ModelLifecycleState.DISCOVERED);
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
        this.serializeAccess = runtimeSupportDescriptor() == io.cortexj.runtime.ThreadSafety.SESSION_SCOPED;
    }

    void lifecycleTo(ModelLifecycleState next) {
        transitionTo(next);
    }

    void attach(RuntimeModel model, ModelRuntime runtime) {
        this.model = model;
        this.runtime = runtime;
        transitionTo(ModelLifecycleState.READY);
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

    void onClose(java.util.function.Consumer<DefaultModelHandle> callback) {
        this.closeCallback = callback;
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
        requireActive("capability");
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
        if (capabilityType == io.cortexj.inference.TextGenerationCapability.class
                && model instanceof io.cortexj.inference.TextGenerationCapability) {
            return capabilityType.cast(new InstrumentedGeneration(
                    (io.cortexj.inference.TextGenerationCapability) model));
        }
        if (capabilityType == io.cortexj.inference.EmbeddingCapability.class
                && model instanceof io.cortexj.inference.EmbeddingCapability) {
            return capabilityType.cast(new InstrumentedEmbedding(
                    (io.cortexj.inference.EmbeddingCapability) model));
        }
        if (capabilityType.isInstance(model)) {
            return capabilityType.cast(model);
        }
        return null;
    }

    private void publishInferenceCompleted(long startNanos, String detail, Long outputTokens) {
        long durationMillis = (System.nanoTime() - startNanos) / 1_000_000L;
        java.util.Map<String, String> attributes = new java.util.LinkedHashMap<String, String>();
        attributes.put("runtime", candidate.runtimeId());
        attributes.put("model", descriptor.canonicalId());
        attributes.put("durationMillis", String.valueOf(durationMillis));
        if (detail != null) {
            attributes.put("detail", detail);
        }
        if (outputTokens != null) {
            attributes.put("outputTokens", String.valueOf(outputTokens));
            if (durationMillis > 0 && outputTokens > 0) {
                attributes.put("tokensPerSecond",
                        String.format(java.util.Locale.ROOT, "%.1f",
                                outputTokens * 1000.0 / durationMillis));
            }
        }
        platform.events().publish(io.cortexj.observability.PlatformEvent.Type.INFERENCE_COMPLETED,
                io.cortexj.observability.PlatformEvent.Level.INFO,
                "inference completed via " + candidate.runtimeId(), attributes);
    }

    private final class InstrumentedGeneration implements io.cortexj.inference.TextGenerationCapability {

        private final io.cortexj.inference.TextGenerationCapability delegate;

        InstrumentedGeneration(io.cortexj.inference.TextGenerationCapability delegate) {
            this.delegate = delegate;
        }

        @Override
        public io.cortexj.inference.GenerationResponse generate(io.cortexj.inference.GenerationRequest request) {
            // audit L-3: the generation dispatch goes through the session lock
            // for SESSION_SCOPED runtimes exactly like execute/stream
            if (serializeAccess) {
                sessionLock.lock();
                try {
                    return generateAndInstrument(request);
                } finally {
                    sessionLock.unlock();
                }
            }
            return generateAndInstrument(request);
        }

        private io.cortexj.inference.GenerationResponse generateAndInstrument(
                io.cortexj.inference.GenerationRequest request) {
            long start = System.nanoTime();
            io.cortexj.inference.GenerationResponse response = delegate.generate(request);
            io.cortexj.inference.TokenUsage usage = response == null ? null : response.usage();
            publishInferenceCompleted(start, "generate",
                    usage != null && usage.isKnown() ? Long.valueOf(usage.outputTokens()) : null);
            return response;
        }

        @Override
        public io.cortexj.stream.TokenStream stream(io.cortexj.inference.GenerationRequest request) {
            // audit L-3: the stream creation dispatches on the runtime model too,
            // so it goes through the session lock for SESSION_SCOPED runtimes
            // exactly like generate/execute
            if (serializeAccess) {
                sessionLock.lock();
                try {
                    return streamAndInstrument(request);
                } finally {
                    sessionLock.unlock();
                }
            }
            return streamAndInstrument(request);
        }

        private io.cortexj.stream.TokenStream streamAndInstrument(
                io.cortexj.inference.GenerationRequest request) {
            long startedNanos = System.nanoTime();
            io.cortexj.stream.TokenStream upstream = delegate.stream(request);
            return new FirstTokenTimingStream(upstream, startedNanos);
        }
    }

    private final class FirstTokenTimingStream implements io.cortexj.stream.TokenStream {

        private final io.cortexj.stream.TokenStream upstream;
        private final long startedNanos;
        private final java.util.concurrent.atomic.AtomicLong tokensSeen =
                new java.util.concurrent.atomic.AtomicLong();
        private volatile Long firstTokenMillis;

        FirstTokenTimingStream(io.cortexj.stream.TokenStream upstream, long startedNanos) {
            this.upstream = upstream;
            this.startedNanos = startedNanos;
        }

        @Override
        public io.cortexj.stream.StreamSubscription subscribe(io.cortexj.stream.TokenConsumer consumer) {
            return upstream.subscribe(new io.cortexj.stream.TokenConsumer() {
                @Override
                public void onToken(io.cortexj.stream.Token token) {
                    long count = tokensSeen.incrementAndGet();
                    if (count == 1) {
                        firstTokenMillis = Long.valueOf(
                                (System.nanoTime() - startedNanos) / 1_000_000L);
                        publishFirstToken(firstTokenMillis.longValue());
                    }
                    consumer.onToken(token);
                }

                @Override
                public void onComplete(io.cortexj.stream.StreamCompletion completion) {
                    long durationMillis = (System.nanoTime() - startedNanos) / 1_000_000L;
                    long outputTokens = tokensSeen.get();
                    java.util.Map<String, String> attributes = new java.util.LinkedHashMap<String, String>();
                    attributes.put("runtime", candidate.runtimeId());
                    attributes.put("model", descriptor.canonicalId());
                    attributes.put("durationMillis", String.valueOf(durationMillis));
                    attributes.put("outputTokens", String.valueOf(outputTokens));
                    // audit 2026-10-08: never publish the string "null" - an
                    // empty stream has no first token, and the listener must
                    // not choke the whole INFERENCE_COMPLETED event on it
                    if (firstTokenMillis != null) {
                        attributes.put("firstTokenMillis", String.valueOf(firstTokenMillis));
                    }
                    if (durationMillis > 0 && outputTokens > 0) {
                        attributes.put("tokensPerSecond", String.format(java.util.Locale.ROOT,
                                "%.1f", outputTokens * 1000.0 / durationMillis));
                    }
                    platform.events().publish(io.cortexj.observability.PlatformEvent.Type.INFERENCE_COMPLETED,
                            io.cortexj.observability.PlatformEvent.Level.INFO,
                            "stream completed via " + candidate.runtimeId(), attributes);
                    consumer.onComplete(completion);
                }

                @Override
                public void onError(io.cortexj.error.CortexJException error) {
                    consumer.onError(error);
                }
            });
        }

        @Override
        public void close() {
            upstream.close();
        }

        private void publishFirstToken(long millis) {
            java.util.Map<String, String> attributes = new java.util.LinkedHashMap<String, String>();
            attributes.put("runtime", candidate.runtimeId());
            attributes.put("model", descriptor.canonicalId());
            attributes.put("firstTokenMillis", String.valueOf(millis));
            platform.events().publish(io.cortexj.observability.PlatformEvent.Type.INFERENCE_FIRST_TOKEN,
                    io.cortexj.observability.PlatformEvent.Level.INFO,
                    "first token after " + millis + " ms", attributes);
        }
    }

    private final class InstrumentedEmbedding implements io.cortexj.inference.EmbeddingCapability {

        private final io.cortexj.inference.EmbeddingCapability delegate;

        InstrumentedEmbedding(io.cortexj.inference.EmbeddingCapability delegate) {
            this.delegate = delegate;
        }

        @Override
        public io.cortexj.inference.EmbeddingResponse embed(io.cortexj.inference.EmbeddingRequest request) {
            // audit L-3: the embedding dispatch goes through the session lock
            // for SESSION_SCOPED runtimes exactly like execute/generate
            if (serializeAccess) {
                sessionLock.lock();
                try {
                    return embedAndInstrument(request);
                } finally {
                    sessionLock.unlock();
                }
            }
            return embedAndInstrument(request);
        }

        private io.cortexj.inference.EmbeddingResponse embedAndInstrument(
                io.cortexj.inference.EmbeddingRequest request) {
            long start = System.nanoTime();
            io.cortexj.inference.EmbeddingResponse response = delegate.embed(request);
            publishInferenceCompleted(start,
                    response == null ? null : "embed:" + response.vectors().size(), null);
            return response;
        }
    }

    @Override
    public boolean supports(TaskCapability task) {
        return model.capabilities().supports(task);
    }

    @Override
    public ModelLifecycleState state() {
        return state.get();
    }

    @Override
    public void waitForReady() throws InterruptedException {
        for (;;) {
            ModelLifecycleState current = state.get();
            if (current == ModelLifecycleState.READY || current == ModelLifecycleState.DEGRADED) {
                return;
            }
            if (current.isTerminal()) {
                throw new IllegalStateException("Model handle is already " + current);
            }
            synchronized (lifecycleMonitor) {
                if (state.get() == current) {
                    lifecycleMonitor.wait(50);
                }
            }
        }
    }

    public InferenceResponse execute(InferenceRequest request) {
        requireActive("execute");
        if (!serializeAccess) {
            return model.execute(request);
        }
        long waitStartedNanos = System.nanoTime();
        sessionLock.lock();
        long queueWaitMillis = (System.nanoTime() - waitStartedNanos) / 1_000_000L;
        try {
            if (queueWaitMillis > 0) {
                publishQueueWait(queueWaitMillis);
            }
            requireActive("execute");
            return model.execute(request);
        } finally {
            sessionLock.unlock();
        }
    }

    private void publishQueueWait(long queueWaitMillis) {
        java.util.Map<String, String> attributes = new java.util.LinkedHashMap<String, String>();
        attributes.put("runtime", candidate.runtimeId());
        attributes.put("model", descriptor.canonicalId());
        attributes.put("queueWaitMillis", String.valueOf(queueWaitMillis));
        platform.events().publish(io.cortexj.observability.PlatformEvent.Type.INFERENCE_COMPLETED,
                io.cortexj.observability.PlatformEvent.Level.INFO,
                "queued for " + queueWaitMillis + " ms before inference", attributes);
    }

    public CompletionStage<InferenceResponse> executeAsync(final InferenceRequest request) {
        requireActive("executeAsync");
        if (serializeAccess) {
            try {
                return CompletableFuture.supplyAsync(new java.util.function.Supplier<InferenceResponse>() {
                    @Override
                    public InferenceResponse get() {
                        return execute(request);
                    }
                }, platform.asyncExecutor());
            } catch (java.util.concurrent.RejectedExecutionException saturated) {
                CompletableFuture<InferenceResponse> rejected = new CompletableFuture<InferenceResponse>();
                rejected.completeExceptionally(new InferenceException(ErrorCode.INFERENCE_FAILED,
                        "Async inference rejected: the async executor queue is saturated "
                                + "(" + saturated.getMessage() + ").",
                        "Retry when in-flight async calls drain, or use the blocking execute()."));
                return rejected;
            }
        }
        return model.executeAsync(request);
    }

    private void transitionTo(ModelLifecycleState next) {
        for (;;) {
            ModelLifecycleState current = state.get();
            if (!current.canTransitionTo(next)) {
                throw new IllegalStateException(
                        "Illegal model lifecycle transition " + current + " -> " + next);
            }
            if (state.compareAndSet(current, next)) {
                synchronized (lifecycleMonitor) {
                    lifecycleMonitor.notifyAll();
                }
                return;
            }
        }
    }

    private void requireActive(String operation) {
        ModelLifecycleState current = state.get();
        if (current == ModelLifecycleState.CLOSING || current.isTerminal()) {
            InferenceException failure = new InferenceException(io.cortexj.ErrorCode.INFERENCE_FAILED,
                    "Cannot " + operation + " on a model handle that is " + current,
                    "Create a new handle via CortexJ.models().load(...).");
            failure.detail("model", descriptor.canonicalId());
            throw failure;
        }
    }

    private boolean beginClose() {
        for (;;) {
            ModelLifecycleState current = state.get();
            if (current == ModelLifecycleState.CLOSING || current.isTerminal()) {
                return false;
            }
            if (state.compareAndSet(current, ModelLifecycleState.CLOSING)) {
                return true;
            }
        }
    }

    private void closeResources() {
        // audit 2026-10-07 (#147): an Error (not just a RuntimeException)
        // must not leave the handle stuck in CLOSING with its cache pin
        // held - best-effort close, then let close() finish the transition
        try {
            if (model != null) {
                model.close();
            }
        } catch (Throwable failure) {
            LOG.log(java.util.logging.Level.FINE,
                    "model close failed for " + descriptor.canonicalId() + "; continuing", failure);
        }
        try {
            if (runtime != null) {
                runtime.close();
            }
        } catch (Throwable failure) {
            LOG.log(java.util.logging.Level.FINE,
                    "runtime close failed for " + descriptor.canonicalId() + "; continuing", failure);
        }
    }

    @Override
    public void close() {
        if (!beginClose()) {
            return;
        }
        // close under the same session lock execute() uses for SESSION_SCOPED
        // runtimes: close-during-execute waits for the in-flight call instead
        // of tearing down the native session under it
        if (serializeAccess) {
            sessionLock.lock();
            try {
                closeResources();
            } finally {
                sessionLock.unlock();
            }
        } else {
            closeResources();
        }
        transitionTo(ModelLifecycleState.CLOSED);
        java.util.function.Consumer<DefaultModelHandle> callback = closeCallback;
        if (callback != null) {
            callback.accept(this);
        }
        platform.events().info(io.cortexj.observability.PlatformEvent.Type.PLATFORM_CLOSED,
                "model handle closed: " + descriptor.canonicalId());
    }
}
