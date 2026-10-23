package io.cortexj.examples.plugin;

import io.cortexj.model.ModelCapabilities;
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

import java.util.Collections;

/**
 * Runtime plugin implementation: a third-party provider registers via
 * ServiceLoader (see META-INF/services in this module) and requires no core changes.
 */
public final class CustomRuntimeProvider implements RuntimeProvider {

    private final RuntimeId id = RuntimeId.of("company-runtime");

    @Override
    public RuntimeId id() {
        return id;
    }

    @Override
    public RuntimeDescriptor descriptor() {
        return RuntimeDescriptor.builder(id)
                .version("1.0.0")
                .format(io.cortexj.model.ArtifactFormat.GGUF)
                .device(DeviceType.CPU)
                .task(TaskCapability.TEXT_GENERATION)
                .nativeRuntime(false)
                .threadSafety(io.cortexj.runtime.ThreadSafety.STATELESS)
                .maturity(RuntimeDescriptor.Maturity.STABLE)
                .build();
    }

    @Override
    public RuntimeProbeResult probe(RuntimeProbeContext context) {
        return RuntimeProbeResult.available("company-runtime 1.0.0 ready");
    }

    @Override
    public RuntimeSupport evaluate(RuntimeEvaluationRequest request) {
        // the provider only evaluates compatibility; the planner makes the final selection
        if (request.artifact() != null
                && request.artifact().format() == io.cortexj.model.ArtifactFormat.GGUF) {
            return RuntimeSupport.full(DeviceType.CPU);
        }
        return RuntimeSupport.unsupported(io.cortexj.planner.PlanReason.negative(
                io.cortexj.planner.ReasonCode.FORMAT_UNSUPPORTED,
                "company-runtime only executes GGUF artifacts"));
    }

    @Override
    public ModelRuntime open(RuntimeOpenRequest request) {
        return new ModelRuntime() {
            @Override
            public RuntimeId runtimeId() {
                return id;
            }

            @Override
            public RuntimeModel load(RuntimeModelRequest loadRequest) {
                return new CompanyRuntimeModel();
            }

            @Override
            public void close() {
            }
        };
    }

    static final class CompanyRuntimeModel implements RuntimeModel,
            io.cortexj.inference.TextGenerationCapability {

        @Override
        public ModelCapabilities capabilities() {
            return ModelCapabilities.of(Collections.singleton(TaskCapability.TEXT_GENERATION),
                    false, false, 1);
        }

        @Override
        public io.cortexj.inference.InferenceResponse execute(
                io.cortexj.inference.InferenceRequest request) {
            return io.cortexj.inference.InferenceResponse.single("company: " + request.singleInput());
        }

        @Override
        public java.util.concurrent.CompletionStage<io.cortexj.inference.InferenceResponse> executeAsync(
                final io.cortexj.inference.InferenceRequest request) {
            return java.util.concurrent.CompletableFuture.completedFuture(execute(request));
        }

        @Override
        public io.cortexj.inference.GenerationResponse generate(
                io.cortexj.inference.GenerationRequest request) {
            return new io.cortexj.inference.GenerationResponse(
                    "company-model reply to: " + request.prompt(),
                    io.cortexj.inference.FinishReason.STOP,
                    io.cortexj.inference.TokenUsage.unknown(), null);
        }

        @Override
        public io.cortexj.stream.TokenStream stream(
                io.cortexj.inference.GenerationRequest request) {
            return io.cortexj.stream.TokenStreams.single("company-stream: " + request.prompt());
        }

        @Override
        public void close() {
        }
    }
}
