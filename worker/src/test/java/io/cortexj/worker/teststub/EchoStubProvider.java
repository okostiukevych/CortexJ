package io.cortexj.worker.teststub;

import io.cortexj.ErrorCode;
import io.cortexj.error.ModelLoadException;
import io.cortexj.inference.EmbeddingCapability;
import io.cortexj.inference.EmbeddingRequest;
import io.cortexj.inference.EmbeddingResponse;
import io.cortexj.inference.FinishReason;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.GenerationResponse;
import io.cortexj.inference.InferenceRequest;
import io.cortexj.inference.InferenceResponse;
import io.cortexj.inference.TextGenerationCapability;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelCapabilities;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.model.TaskCapability;
import io.cortexj.runtime.DeviceType;
import io.cortexj.runtime.RuntimeDescriptor;
import io.cortexj.runtime.RuntimeId;
import io.cortexj.spi.runtime.ModelRuntime;
import io.cortexj.spi.runtime.RuntimeEvaluationRequest;
import io.cortexj.spi.runtime.RuntimeModel;
import io.cortexj.spi.runtime.RuntimeModelRequest;
import io.cortexj.spi.runtime.RuntimeOpenRequest;
import io.cortexj.spi.runtime.RuntimeProbeContext;
import io.cortexj.spi.runtime.RuntimeProbeResult;
import io.cortexj.spi.runtime.RuntimeProvider;
import io.cortexj.spi.runtime.RuntimeSupport;
import io.cortexj.stream.internal.TokenStreamPublisher;
import io.cortexj.stream.TokenStream;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Deterministic in-process stub runtime used by the worker protocol tests.
 * Registered under META-INF/services with id "worker-stub".
 */
public class EchoStubProvider implements RuntimeProvider {

    public static final String ID = "worker-stub";
    /** execute() sleeps this long when the request parameter "slow" is true. */
    public static final long SLOW_EXECUTE_MILLIS = 60_000L;

    private final RuntimeId id = RuntimeId.of(ID);
    private final RuntimeDescriptor descriptor = RuntimeDescriptor.builder(id)
            .version("1.0")
            .format(ArtifactFormat.ONNX)
            .device(DeviceType.CPU)
            .task(TaskCapability.EMBEDDING)
            .task(TaskCapability.TEXT_GENERATION)
            .minJavaFeatureVersion(8)
            .streamingSupported(true)
            .nativeRuntime(false)
            .threadSafety(io.cortexj.runtime.ThreadSafety.STATELESS)
            .maturity(RuntimeDescriptor.Maturity.STABLE)
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
        return RuntimeProbeResult.available("worker stub runtime ready");
    }

    @Override
    public RuntimeSupport evaluate(RuntimeEvaluationRequest request) {
        if (request.artifact() != null && request.artifact().format() == ArtifactFormat.ONNX) {
            return RuntimeSupport.full(DeviceType.CPU);
        }
        return RuntimeSupport.unsupported(io.cortexj.planner.PlanReason.negative(
                io.cortexj.planner.ReasonCode.FORMAT_UNSUPPORTED,
                "worker-stub only executes ONNX artifacts"));
    }

    @Override
    public ModelRuntime open(RuntimeOpenRequest request) {
        return new StubModelRuntime();
    }

    final class StubModelRuntime implements ModelRuntime {

        @Override
        public RuntimeId runtimeId() {
            return id;
        }

        @Override
        public RuntimeModel load(RuntimeModelRequest request) {
            Path modelPath = request.artifactPath();
            if (modelPath == null || !Files.isRegularFile(modelPath)) {
                ModelLoadException failure = new ModelLoadException(ErrorCode.LOAD_FAILED,
                        "stub artifact path is missing or does not exist: " + modelPath,
                        "Create the file first (the stub validates existence only).");
                failure.detail("path", String.valueOf(modelPath));
                throw failure;
            }
            return new StubModel();
        }

        @Override
        public void close() {
        }
    }

    final class StubModel implements RuntimeModel, TextGenerationCapability, EmbeddingCapability {

        @Override
        public ModelCapabilities capabilities() {
            java.util.Set<TaskCapability> tasks = new java.util.LinkedHashSet<TaskCapability>();
            Collections.addAll(tasks, TaskCapability.EMBEDDING, TaskCapability.TEXT_GENERATION);
            return ModelCapabilities.of(tasks, true, false, 4);
        }

        @Override
        public InferenceResponse execute(InferenceRequest request) {
            if (Boolean.TRUE.equals(request.parameters().get("slow"))) {
                try {
                    Thread.sleep(SLOW_EXECUTE_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            java.util.List<String> outputs = new java.util.ArrayList<String>();
            for (String input : request.inputs()) {
                outputs.add("stub:" + input);
            }
            Map<String, Object> metadata = new LinkedHashMap<String, Object>();
            metadata.put("stub", Boolean.TRUE);
            return new InferenceResponse(outputs, metadata);
        }

        @Override
        public CompletionStage<InferenceResponse> executeAsync(final InferenceRequest request) {
            return CompletableFuture.completedFuture(execute(request));
        }

        @Override
        public GenerationResponse generate(GenerationRequest request) {
            String prompt = request.isChat()
                    ? request.messages().get(request.messages().size() - 1).content()
                    : request.prompt();
            String text = "stub-generated:" + prompt;
            return new GenerationResponse(text, FinishReason.STOP,
                    new io.cortexj.inference.TokenUsage(prompt.length(), text.length()), null);
        }

        @Override
        public TokenStream stream(GenerationRequest request) {
            final TokenStreamPublisher publisher = new TokenStreamPublisher();
            final AtomicBoolean cancelled = new AtomicBoolean(false);
            publisher.onCancel(new Runnable() {
                @Override
                public void run() {
                    cancelled.set(true);
                }
            });
            final int tokens = request.maxTokens() == null ? 3 : request.maxTokens().intValue();
            Thread emitter = new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int i = 0; i < tokens && !cancelled.get(); i++) {
                        publisher.publish("t" + i);
                        try {
                            Thread.sleep(100L);
                        } catch (InterruptedException stopped) {
                            return;
                        }
                    }
                    if (!cancelled.get()) {
                        publisher.complete(FinishReason.STOP, tokens, tokens);
                    }
                }
            }, "worker-stub-token-emitter");
            emitter.setDaemon(true);
            emitter.start();
            return publisher.stream();
        }

        @Override
        public EmbeddingResponse embed(EmbeddingRequest request) {
            java.util.List<float[]> vectors = new java.util.ArrayList<float[]>();
            for (String text : request.texts()) {
                vectors.add(new float[]{1.0f, text.length() * 0.5f});
            }
            return new EmbeddingResponse(vectors);
        }

        @Override
        public void close() {
        }
    }
}
