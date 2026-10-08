package io.cortexj.runtime.jinfer;

import io.cortexj.ErrorCode;
import io.cortexj.error.CortexJException;
import io.cortexj.error.InferenceException;
import io.cortexj.error.ModelLoadException;
import io.cortexj.error.RuntimeUnavailableException;
import io.cortexj.inference.EmbeddingCapability;
import io.cortexj.inference.EmbeddingRequest;
import io.cortexj.inference.EmbeddingResponse;
import io.cortexj.inference.FinishReason;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.GenerationResponse;
import io.cortexj.inference.InferenceRequest;
import io.cortexj.inference.InferenceResponse;
import io.cortexj.inference.RerankingCapability;
import io.cortexj.inference.RerankingRequest;
import io.cortexj.inference.RerankingResponse;
import io.cortexj.inference.TextGenerationCapability;
import io.cortexj.inference.TokenUsage;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ChatMessage;
import io.cortexj.model.ModelCapabilities;
import io.cortexj.model.TaskCapability;
import io.cortexj.planner.PlanReason;
import io.cortexj.planner.ReasonCode;
import io.cortexj.runtime.DeviceType;
import io.cortexj.runtime.RuntimeDescriptor;
import io.cortexj.runtime.RuntimeId;
import io.cortexj.runtime.ThreadSafety;
import io.cortexj.spi.runtime.ModelRuntime;
import io.cortexj.spi.runtime.RuntimeEvaluationRequest;
import io.cortexj.spi.runtime.RuntimeModel;
import io.cortexj.spi.runtime.RuntimeModelRequest;
import io.cortexj.spi.runtime.RuntimeOpenRequest;
import io.cortexj.spi.runtime.RuntimeProbeContext;
import io.cortexj.spi.runtime.RuntimeProbeResult;
import io.cortexj.spi.runtime.RuntimeProvider;
import io.cortexj.spi.runtime.RuntimeSupport;
import io.cortexj.stream.StreamCompletion;
import io.cortexj.stream.StreamSubscription;
import io.cortexj.stream.Token;
import io.cortexj.stream.TokenConsumer;
import io.cortexj.stream.TokenStream;

import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Optional JInfer {@link RuntimeProvider} (spec v3.0 sections 0/19, issue #160).
 *
 * <p>Covers Quixotic JInfer {@code 0.3.1} ({@code com.qxotic:jinfer-chat},
 * Apache-2.0, Java 25+). JInfer types never leak into shared signatures:
 * the planner only sees {@link RuntimeDescriptor}/{@link RuntimeSupport}.
 * Removing this module never breaks core.
 */
public class JInferRuntimeProvider implements RuntimeProvider {

    private static final Logger LOG = Logger.getLogger(JInferRuntimeProvider.class.getName());

    static final String PROVIDER_ID = "jinfer";

    /** Tracked JInfer release. Bump together with {@code jinfer.version} in the parent POM. */
    static final String JINFER_VERSION = "0.3.1";

    static final int MIN_JAVA_FEATURE_VERSION = 25;

    /** Default generation cap when the request carries none. */
    static final int DEFAULT_MAX_TOKENS = 512;

    /** Inference state capacity for embedding/reranking batches of short texts. */
    static final int BATCH_STATE_CAPACITY = 2048;

    private final RuntimeId id = RuntimeId.of(PROVIDER_ID);
    private final RuntimeDescriptor descriptor = RuntimeDescriptor.builder(id)
            .version(JINFER_VERSION)
            .format(ArtifactFormat.GGUF)
            .device(DeviceType.CPU)
            .task(TaskCapability.TEXT_GENERATION)
            .task(TaskCapability.EMBEDDING)
            .task(TaskCapability.RERANKING)
            .minJavaFeatureVersion(MIN_JAVA_FEATURE_VERSION)
            .streamingSupported(true)
            .structuredOutputNative(true)
            .nativeRuntime(true)
            .threadSafety(ThreadSafety.SESSION_SCOPED)
            .maturity(RuntimeDescriptor.Maturity.BETA)
            .metadata("engine", "Quixotic JInfer " + JINFER_VERSION)
            .metadata("license", "Apache-2.0")
            .metadata("artifact", "com.qxotic:jinfer-chat:" + JINFER_VERSION)
            .metadata("streaming", "token-granular via ReplySink (CONTENT channel)")
            .build();

    @Override
    public RuntimeId id() {
        return id;
    }

    @Override
    public RuntimeDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public RuntimeProbeResult probe(RuntimeProbeContext context) {
        try {
            if (context != null && context.environment() != null
                    && context.environment().jvmFeatureVersion() < MIN_JAVA_FEATURE_VERSION) {
                RuntimeUnavailableException failure = new RuntimeUnavailableException(
                        ErrorCode.RUNTIME_JVM_INCOMPATIBLE,
                        "JInfer " + JINFER_VERSION + " requires Java 25+, detected Java "
                                + context.environment().jvmFeatureVersion() + ".",
                        "Use cortexj-starter-java25 on JDK 25 or pick another runtime provider.");
                failure.detail("runtime", id.value());
                failure.detail("minJava", String.valueOf(MIN_JAVA_FEATURE_VERSION));
                return RuntimeProbeResult.unavailable("JInfer requires Java 25+", failure);
            }
            try {
                Class.forName("com.qxotic.jinfer.chat.Models");
            } catch (Throwable missing) {
                RuntimeUnavailableException failure = new RuntimeUnavailableException(
                        ErrorCode.RUNTIME_NOT_INSTALLED,
                        "JInfer classes are not on the classpath: " + missing,
                        "Add the 'cortexj-runtime-jinfer' dependency (brings com.qxotic:jinfer-chat).",
                        missing);
                failure.detail("runtime", id.value());
                return RuntimeProbeResult.unavailable("JInfer is not installed", failure);
            }
            Set<String> architectures;
            try {
                architectures = new java.util.TreeSet<String>(
                        com.qxotic.jinfer.chat.Models.supportedArchitectures());
            } catch (Throwable families) {
                RuntimeUnavailableException failure = new RuntimeUnavailableException(
                        ErrorCode.RUNTIME_MISCONFIGURED,
                        "JInfer model-family dispatch is broken: " + families,
                        "Verify the jinfer-chat version matches " + JINFER_VERSION + ".",
                        families);
                failure.detail("runtime", id.value());
                return RuntimeProbeResult.misconfigured("JInfer family dispatch is broken", failure);
            }
            Map<String, String> details = new LinkedHashMap<String, String>();
            details.put("jinfer", JINFER_VERSION);
            details.put("architectures", architectures.toString());
            if (architectures.isEmpty()) {
                return RuntimeProbeResult.degraded(
                        "JInfer API is present but no model-family artifact (jinfer-qwen3, "
                                + "jinfer-llama, ...) is on the classpath");
            }
            return RuntimeProbeResult.available("JInfer " + JINFER_VERSION + " is ready", details);
        } catch (Throwable probeFailure) {
            // probe must never throw (TCK: probeNeverThrows)
            return RuntimeProbeResult.unavailable("JInfer probe failed: " + probeFailure);
        }
    }

    @Override
    public RuntimeSupport evaluate(RuntimeEvaluationRequest request) {
        if (request == null || request.artifact() == null
                || request.artifact().format() != ArtifactFormat.GGUF) {
            return RuntimeSupport.unsupported(PlanReason.negative(
                    ReasonCode.FORMAT_UNSUPPORTED,
                    "jinfer executes GGUF checkpoints only"));
        }
        return RuntimeSupport.full(DeviceType.CPU, PlanReason.positive(
                ReasonCode.NATIVE_ARTIFACT_AVAILABLE,
                "jinfer executes GGUF checkpoints directly, no conversion needed"));
    }

    @Override
    public ModelRuntime open(RuntimeOpenRequest request) {
        Executor executor = request == null ? null : request.executor();
        return new JInferModelRuntime(executor);
    }

    /**
     * Maps a CortexJ generation/chat request onto JInfer messages.
     * A prompt-only request becomes a single USER message.
     */
    static List<com.qxotic.jinfer.chat.Message> toJInferMessages(GenerationRequest request) {
        List<com.qxotic.jinfer.chat.Message> messages =
                new ArrayList<com.qxotic.jinfer.chat.Message>();
        if (request.messages().isEmpty()) {
            messages.add(com.qxotic.jinfer.chat.Message.user(request.prompt()));
            return messages;
        }
        for (ChatMessage message : request.messages()) {
            String content = message.content();
            switch (message.role()) {
                case SYSTEM:
                    messages.add(com.qxotic.jinfer.chat.Message.system(content));
                    break;
                case ASSISTANT:
                    messages.add(com.qxotic.jinfer.chat.Message.assistant(content));
                    break;
                case TOOL:
                    messages.add(new com.qxotic.jinfer.chat.Message(
                            com.qxotic.jinfer.chat.Role.TOOL, content));
                    break;
                case USER:
                default:
                    messages.add(com.qxotic.jinfer.chat.Message.user(content));
                    break;
            }
        }
        return messages;
    }

    static FinishReason toFinishReason(
            com.qxotic.jinfer.llm.Generator.FinishReason reason) {
        if (reason == null) {
            return FinishReason.OTHER;
        }
        switch (reason) {
            case STOP:
                return FinishReason.STOP;
            case LENGTH:
                return FinishReason.LENGTH;
            default:
                return FinishReason.OTHER;
        }
    }

    final class JInferModelRuntime implements ModelRuntime {

        private final Executor executor;
        private final AtomicBoolean closed = new AtomicBoolean();

        JInferModelRuntime(Executor executor) {
            this.executor = executor;
        }

        @Override
        public RuntimeId runtimeId() {
            return id;
        }

        @Override
        public RuntimeModel load(RuntimeModelRequest request) {
            Path artifactPath = request == null ? null : request.artifactPath();
            if (artifactPath == null || !Files.isRegularFile(artifactPath)) {
                ModelLoadException failure = new ModelLoadException(ErrorCode.LOAD_FAILED,
                        "JInfer GGUF artifact path is missing or does not exist: " + artifactPath,
                        "Verify the artifact was downloaded or referenced correctly.");
                failure.detail("path", String.valueOf(artifactPath));
                failure.detail("runtime", id.value());
                throw failure;
            }
            // Capability-based dispatch: a GGUF file can hold a chat model,
            // an embedding model or a reranker. Chat checkpoints are distinct
            // (the family provider declines anything else), but the embedding
            // and reranking loaders of one family accept each other's files
            // (verified live on Qwen3) — order alone cannot tell them apart.
            // So the retrieval kind reported by JInfer decides; only when it
            // is unavailable does the declared model task break the tie.
            // A decline is never silent: every attempt is recorded, and the
            // final error carries them all.
            List<String> attempts = new ArrayList<String>();
            try {
                return openChatModel(artifactPath);
            } catch (Throwable chatFailure) {
                attempts.add("chat: " + shortCause(chatFailure));
            }
            com.qxotic.jinfer.chat.ModelProvider.Retrieval retrieval = retrievalKind(artifactPath);
            if (retrieval == com.qxotic.jinfer.chat.ModelProvider.Retrieval.RERANKING) {
                return openRerankingModel(artifactPath);
            }
            if (retrieval == com.qxotic.jinfer.chat.ModelProvider.Retrieval.EMBEDDING) {
                return openEmbeddingModel(artifactPath);
            }
            if (prefersReranking(request)) {
                try {
                    return openRerankingModel(artifactPath);
                } catch (Throwable rerankingFailure) {
                    attempts.add("reranking: " + shortCause(rerankingFailure));
                }
                try {
                    return openEmbeddingModel(artifactPath);
                } catch (Throwable embeddingFailure) {
                    attempts.add("embedding: " + shortCause(embeddingFailure));
                }
            } else {
                try {
                    return openEmbeddingModel(artifactPath);
                } catch (Throwable embeddingFailure) {
                    attempts.add("embedding: " + shortCause(embeddingFailure));
                }
                try {
                    return openRerankingModel(artifactPath);
                } catch (Throwable rerankingFailure) {
                    attempts.add("reranking: " + shortCause(rerankingFailure));
                }
            }
            ModelLoadException failure = new ModelLoadException(ErrorCode.LOAD_FAILED,
                    "JInfer cannot execute " + artifactPath + " as chat, embedding or reranking model.",
                    "Add the matching JInfer model-family artifact (jinfer-qwen3, jinfer-llama, "
                            + "jinfer-lfm2, jinfer-qwen35, jinfer-gemma4 or jinfer-models-all).");
            failure.detail("path", artifactPath.toString());
            failure.detail("runtime", id.value());
            failure.detail("attempts", attempts.toString());
            throw failure;
        }

        private JInferModel openChatModel(Path artifactPath) {
            try {
                com.qxotic.jinfer.chat.ChatEngine engine =
                        new com.qxotic.jinfer.chat.ChatEngine(artifactPath);
                return new JInferModel(JInferModel.Kind.CHAT, engine, null, null, null, executor);
            } catch (Throwable e) {
                throw typedLoadError(artifactPath, e);
            }
        }

        private JInferModel openEmbeddingModel(Path artifactPath) {
            Arena arena = Arena.ofShared();
            boolean arenaOwned = true;
            try {
                com.qxotic.jinfer.chat.LoadedEmbedder<?> embedder =
                        com.qxotic.jinfer.chat.Models.loadEmbedder(artifactPath, arena);
                arenaOwned = false;
                return new JInferModel(JInferModel.Kind.EMBEDDING, null, embedder, null, arena, executor);
            } catch (Throwable e) {
                if (arenaOwned) {
                    try {
                        arena.close();
                    } catch (Throwable ignored) {
                    }
                }
                throw typedLoadError(artifactPath, e);
            }
        }

        private JInferModel openRerankingModel(Path artifactPath) {
            Arena arena = Arena.ofShared();
            boolean arenaOwned = true;
            try {
                com.qxotic.jinfer.chat.LoadedReranker<?> reranker =
                        com.qxotic.jinfer.chat.Models.loadReranker(artifactPath, arena);
                arenaOwned = false;
                return new JInferModel(JInferModel.Kind.RERANKING, null, null, reranker, arena, executor);
            } catch (Throwable e) {
                if (arenaOwned) {
                    try {
                        arena.close();
                    } catch (Throwable ignored) {
                    }
                }
                throw typedLoadError(artifactPath, e);
            }
        }

        private com.qxotic.jinfer.chat.ModelProvider.Retrieval retrievalKind(Path artifactPath) {
            try {
                java.util.Optional<com.qxotic.jinfer.chat.ModelProvider.Retrieval> retrieval =
                        com.qxotic.jinfer.chat.Models.retrieval(artifactPath);
                if (retrieval != null && retrieval.isPresent()) {
                    return retrieval.get();
                }
            } catch (Throwable ignored) {
            }
            return null;
        }

        private boolean prefersReranking(RuntimeModelRequest request) {
            try {
                if (request != null && request.model() != null && request.model().tasks() != null) {
                    Set<TaskCapability> tasks = request.model().tasks();
                    return tasks.contains(TaskCapability.RERANKING)
                            && !tasks.contains(TaskCapability.EMBEDDING);
                }
            } catch (Throwable ignored) {
            }
            return false;
        }

        private String shortCause(Throwable failure) {            Throwable root = failure;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            String message = root.getMessage();
            String name = root.getClass().getSimpleName();
            return message == null ? name : name + ": " + message;
        }

        private RuntimeException typedLoadError(Path artifactPath, Throwable cause) {            if (cause instanceof CortexJException) {
                return (CortexJException) cause;
            }
            ModelLoadException failure = new ModelLoadException(ErrorCode.LOAD_FAILED,
                    "JInfer cannot load " + artifactPath + ": " + cause, cause);
            failure.detail("path", artifactPath.toString());
            failure.detail("runtime", id.value());
            return failure;
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    final class JInferModel implements RuntimeModel, TextGenerationCapability,
            EmbeddingCapability, RerankingCapability {

        enum Kind { CHAT, EMBEDDING, RERANKING }

        private final Kind kind;
        private final com.qxotic.jinfer.chat.ChatEngine engine;
        private final com.qxotic.jinfer.chat.LoadedEmbedder<?> embedder;
        private final com.qxotic.jinfer.chat.LoadedReranker<?> reranker;
        private final Arena arena;
        private final Executor executor;
        private final AtomicBoolean closed = new AtomicBoolean();

        JInferModel(Kind kind,
                    com.qxotic.jinfer.chat.ChatEngine engine,
                    com.qxotic.jinfer.chat.LoadedEmbedder<?> embedder,
                    com.qxotic.jinfer.chat.LoadedReranker<?> reranker,
                    Arena arena,
                    Executor executor) {
            this.kind = kind;
            this.engine = engine;
            this.embedder = embedder;
            this.reranker = reranker;
            this.arena = arena;
            this.executor = executor;
        }

        @Override
        public ModelCapabilities capabilities() {
            switch (kind) {
                case CHAT:
                    return ModelCapabilities.of(tasks(TaskCapability.TEXT_GENERATION), true, true, 1);
                case EMBEDDING:
                    return ModelCapabilities.of(tasks(TaskCapability.EMBEDDING), false, false, 1);
                case RERANKING:
                default:
                    return ModelCapabilities.of(tasks(TaskCapability.RERANKING), false, false, 1);
            }
        }

        private Set<TaskCapability> tasks(TaskCapability task) {
            Set<TaskCapability> set = new LinkedHashSet<TaskCapability>();
            set.add(task);
            return set;
        }

        @Override
        public InferenceResponse execute(InferenceRequest request) {
            List<String> inputs = request == null
                    ? Collections.<String>emptyList() : request.inputs();
            switch (kind) {
                case CHAT: {
                    String prompt = inputs.isEmpty() ? "" : inputs.get(0);
                    GenerationResponse response = generate(GenerationRequest.prompt(prompt));
                    return InferenceResponse.single(response.text());
                }
                case EMBEDDING: {
                    EmbeddingResponse embedding = embed(EmbeddingRequest.texts(inputs));
                    List<String> outputs = new ArrayList<String>();
                    for (float[] vector : embedding.vectors()) {
                        outputs.add(java.util.Arrays.toString(vector));
                    }
                    Map<String, Object> metadata = new LinkedHashMap<String, Object>();
                    metadata.put("vectors", Integer.valueOf(embedding.vectors().size()));
                    return new InferenceResponse(outputs, metadata);
                }
                case RERANKING:
                default: {
                    String query = inputs.isEmpty() ? "" : inputs.get(0);
                    List<String> documents = inputs.size() > 1
                            ? new ArrayList<String>(inputs.subList(1, inputs.size()))
                            : Collections.<String>emptyList();
                    RerankingResponse reranked = rerank(RerankingRequest.of(query, documents));
                    List<String> outputs = new ArrayList<String>();
                    for (RerankingResponse.ScoredDocument scored : reranked.scores()) {
                        outputs.add(scored.index() + ":" + scored.score());
                    }
                    return new InferenceResponse(outputs, Collections.<String, Object>emptyMap());
                }
            }
        }

        @Override
        public CompletionStage<InferenceResponse> executeAsync(final InferenceRequest request) {
            if (executor == null) {
                return CompletableFuture.completedFuture(execute(request));
            }
            return CompletableFuture.supplyAsync(new java.util.function.Supplier<InferenceResponse>() {
                @Override
                public InferenceResponse get() {
                    return execute(request);
                }
            }, executor);
        }

        @Override
        public GenerationResponse generate(GenerationRequest request) {
            requireKind(Kind.CHAT, TaskCapability.TEXT_GENERATION);
            try {
                synchronized (engine) {
                    ensureOpen();
                    com.qxotic.jinfer.chat.ChatEngine.Completion completion =
                            engine.complete(toJInferRequest(request));
                    String text = completion.reply().text();
                    Map<String, String> metadata = new LinkedHashMap<String, String>();
                    metadata.put("runtime", id.value());
                    metadata.put("model", engine.modelName());
                    return new GenerationResponse(text,
                            toFinishReason(completion.result().finishReason()),
                            new TokenUsage(completion.promptTokens(),
                                    completion.result().completionTokens()),
                            metadata);
                }
            } catch (CortexJException e) {
                throw e;
            } catch (Throwable e) {
                InferenceException failure = new InferenceException(ErrorCode.INFERENCE_FAILED,
                        "JInfer generation failed: " + e.getMessage(), e);
                failure.detail("runtime", id.value());
                throw failure;
            }
        }

        private com.qxotic.jinfer.chat.ChatEngine.Request toJInferRequest(GenerationRequest request) {
            List<com.qxotic.jinfer.chat.Message> messages = toJInferMessages(request);
            int maxTokens = request.maxTokens() != null
                    ? request.maxTokens().intValue() : DEFAULT_MAX_TOKENS;
            com.qxotic.jinfer.llm.Sampling sampling = engine.loaded()
                    .samplingDefaults()
                    .resolve(toFloat(request.temperature()), toFloat(request.topP()),
                            request.topK(), null, request.seed());
            return com.qxotic.jinfer.chat.ChatEngine.Request
                    .builder(messages, sampling)
                    .maxOutputTokens(maxTokens)
                    .stops(new ArrayList<String>(request.stopSequences()))
                    .build();
        }

        private com.qxotic.jinfer.chat.ChatEngine.Completion completeWithSink(
                GenerationRequest request,
                com.qxotic.jinfer.chat.ChatEngine.ReplySink sink) {
            synchronized (engine) {
                ensureOpen();
                return engine.complete(toJInferRequest(request), sink);
            }
        }

        @Override
        public TokenStream stream(final GenerationRequest request) {
            requireKind(Kind.CHAT, TaskCapability.TEXT_GENERATION);
            // Token-granular streaming over the ChatEngine ReplySink path:
            // CONTENT-channel deltas become tokens; exactly one subscription,
            // at most one terminal; cancel propagates into JInfer.
            return new TokenStream() {
                private final AtomicBoolean subscribed = new AtomicBoolean();
                private final AtomicBoolean terminalDelivered = new AtomicBoolean();
                private final AtomicBoolean cancelled = new AtomicBoolean();

                @Override
                public StreamSubscription subscribe(TokenConsumer consumer) {
                    if (!subscribed.compareAndSet(false, true)) {
                        throw new IllegalStateException("this token stream supports a single subscription");
                    }
                    Runnable task = new Runnable() {
                        @Override
                        public void run() {
                            try {
                                final AtomicInteger index = new AtomicInteger();
                                com.qxotic.jinfer.chat.ChatEngine.ReplySink sink =
                                        new com.qxotic.jinfer.chat.ChatEngine.ReplySink() {
                                            @Override
                                            public void on(com.qxotic.jinfer.chat.ChatEngine.Delta delta) {
                                                if (cancelled.get()) {
                                                    return;
                                                }
                                                if (delta != null && delta.text() != null
                                                        && !delta.text().isEmpty()
                                                        && com.qxotic.jinfer.chat.Channel.CONTENT
                                                                .equals(delta.channel())) {
                                                    consumer.onToken(new Token(delta.text(),
                                                            index.getAndIncrement(), null));
                                                }
                                            }

                                            @Override
                                            public boolean cancelled() {
                                                return cancelled.get();
                                            }
                                        };
                                com.qxotic.jinfer.chat.ChatEngine.Completion completion =
                                        completeWithSink(request, sink);
                                if (cancelled.get()) {
                                    deliverError(consumer, cancelledError());
                                } else {
                                    deliverComplete(consumer, new StreamCompletion(
                                            toFinishReason(completion.result().finishReason()),
                                            new TokenUsage(completion.promptTokens(),
                                                    completion.result().completionTokens())));
                                }
                            } catch (Throwable e) {
                                deliverError(consumer, toInferenceError(e));
                            }
                        }
                    };
                    if (executor != null) {
                        executor.execute(task);
                    } else {
                        Thread thread = new Thread(task, "cortexj-jinfer-stream");
                        thread.setDaemon(true);
                        thread.start();
                    }
                    return new StreamSubscription() {
                        @Override
                        public void cancel() {
                            if (cancelled.compareAndSet(false, true)) {
                                deliverError(consumer, cancelledError());
                            }
                        }
                    };
                }

                private void deliverComplete(TokenConsumer consumer, StreamCompletion completion) {
                    if (terminalDelivered.compareAndSet(false, true)) {
                        consumer.onComplete(completion);
                    }
                }

                private void deliverError(TokenConsumer consumer, CortexJException error) {
                    if (terminalDelivered.compareAndSet(false, true)) {
                        consumer.onError(error);
                    }
                }

                private CortexJException cancelledError() {
                    return new InferenceException(ErrorCode.INFERENCE_CANCELLED,
                            "JInfer stream was cancelled.", "Retry the request without cancelling.");
                }

                private CortexJException toInferenceError(Throwable e) {
                    if (e instanceof CortexJException) {
                        return (CortexJException) e;
                    }
                    return new InferenceException(ErrorCode.INFERENCE_STREAM_FAILED,
                            "JInfer streaming failed: " + e.getMessage(),
                            "Retry the request; check the model artifact for the chat architecture.", e);
                }

                @Override
                public void close() {
                }
            };
        }

        @Override
        public EmbeddingResponse embed(EmbeddingRequest request) {
            requireKind(Kind.EMBEDDING, TaskCapability.EMBEDDING);
            List<String> texts = request == null
                    ? Collections.<String>emptyList() : request.texts();
            try {
                return embedAll(embedder, texts);
            } catch (CortexJException e) {
                throw e;
            } catch (Throwable e) {
                InferenceException failure = new InferenceException(ErrorCode.INFERENCE_FAILED,
                        "JInfer embedding failed: " + e.getMessage(), e);
                failure.detail("runtime", id.value());
                throw failure;
            }
        }

        private <S extends com.qxotic.jinfer.ContextState> EmbeddingResponse embedAll(
                com.qxotic.jinfer.chat.LoadedEmbedder<S> loaded, List<String> texts) {
            ensureOpen();
            final List<float[]> vectors = new ArrayList<float[]>();
            S state = loaded.model().newState(BATCH_STATE_CAPACITY);
            try {
                synchronized (loaded) {
                    ensureOpen();
                    loaded.embedAll(state, new ArrayList<String>(texts),
                            new java.util.function.Consumer<float[]>() {
                                @Override
                                public void accept(float[] vector) {
                                    vectors.add(vector.clone());
                                }
                            });
                }
            } finally {
                try {
                    state.close();
                } catch (Throwable ignored) {
                }
            }
            return new EmbeddingResponse(vectors);
        }

        @Override
        public RerankingResponse rerank(RerankingRequest request) {
            requireKind(Kind.RERANKING, TaskCapability.RERANKING);
            String query = request == null ? "" : request.query();
            List<String> documents = request == null
                    ? Collections.<String>emptyList()
                    : new ArrayList<String>(request.documents());
            try {
                return scoreAll(reranker, query, documents);
            } catch (CortexJException e) {
                throw e;
            } catch (Throwable e) {
                InferenceException failure = new InferenceException(ErrorCode.INFERENCE_FAILED,
                        "JInfer reranking failed: " + e.getMessage(), e);
                failure.detail("runtime", id.value());
                throw failure;
            }
        }

        private <S extends com.qxotic.jinfer.ContextState> RerankingResponse scoreAll(
                com.qxotic.jinfer.chat.LoadedReranker<S> loaded,
                String query, List<String> documents) {
            ensureOpen();
            final List<Double> scores = new ArrayList<Double>();
            S state = loaded.model().newState(BATCH_STATE_CAPACITY);
            try {
                synchronized (loaded) {
                    ensureOpen();
                    loaded.scoreAll(state, loaded.reranker().defaultInstruction(), query,
                            new ArrayList<String>(documents),
                            new java.util.function.DoubleConsumer() {
                                @Override
                                public void accept(double score) {
                                    scores.add(Double.valueOf(score));
                                }
                            });
                }
            } finally {
                try {
                    state.close();
                } catch (Throwable ignored) {
                }
            }
            List<RerankingResponse.ScoredDocument> ranked =
                    new ArrayList<RerankingResponse.ScoredDocument>();
            for (int index = 0; index < scores.size(); index++) {
                ranked.add(new RerankingResponse.ScoredDocument(index, scores.get(index).doubleValue()));
            }
            return new RerankingResponse(ranked);
        }

        private void requireKind(Kind expected, TaskCapability task) {
            if (kind != expected) {
                InferenceException failure = new InferenceException(
                        ErrorCode.INFERENCE_UNSUPPORTED_TASK,
                        "This JInfer model supports " + kind + " but the request needs " + expected + ".",
                        "Load the matching GGUF artifact for " + task.commonTag() + ".");
                failure.detail("runtime", id.value());
                failure.detail("modelKind", kind.name());
                throw failure;
            }
        }

        private void ensureOpen() {
            if (closed.get()) {
                throw new InferenceException(ErrorCode.INFERENCE_FAILED,
                        "model is closed", "Reopen the model handle.");
            }
        }

        private Float toFloat(Double value) {
            return value == null ? null : Float.valueOf(value.floatValue());
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            if (engine != null) {
                try {
                    synchronized (engine) {
                        engine.close();
                    }
                } catch (Throwable ignored) {
                }
            }
            if (arena != null) {
                try {
                    arena.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
