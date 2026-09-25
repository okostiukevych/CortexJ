package io.cortexj.core;

import io.cortexj.CortexJ;
import io.cortexj.inference.EmbeddingCapability;
import io.cortexj.inference.EmbeddingRequest;
import io.cortexj.inference.EmbeddingResponse;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.GenerationResponse;
import io.cortexj.inference.InferenceRequest;
import io.cortexj.inference.InferenceResponse;
import io.cortexj.inference.TextGenerationCapability;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.model.ModelCapabilities;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelLifecycleState;
import io.cortexj.model.ModelReference;
import io.cortexj.model.TaskCapability;
import io.cortexj.planner.ExecutionRequest;
import io.cortexj.runtime.DeviceType;
import io.cortexj.runtime.RuntimeDescriptor;
import io.cortexj.runtime.RuntimeId;
import io.cortexj.spi.repository.ModelRepositoryProvider;
import io.cortexj.spi.repository.RepositoryId;
import io.cortexj.spi.repository.ResolvedArtifact;
import io.cortexj.spi.repository.ResolvedModel;
import io.cortexj.spi.repository.ResolutionContext;
import io.cortexj.spi.runtime.ModelRuntime;
import io.cortexj.spi.runtime.RuntimeEvaluationRequest;
import io.cortexj.spi.runtime.RuntimeModel;
import io.cortexj.spi.runtime.RuntimeModelRequest;
import io.cortexj.spi.runtime.RuntimeOpenRequest;
import io.cortexj.spi.runtime.RuntimeProbeContext;
import io.cortexj.spi.runtime.RuntimeProbeResult;
import io.cortexj.spi.runtime.RuntimeProvider;
import io.cortexj.spi.runtime.RuntimeSupport;
import io.cortexj.stream.TokenStream;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndToEndLoadTest {

    private static final ModelArtifact MODEL_ARTIFACT = new ModelArtifact() {
        @Override
        public String id() {
            return "test-model.gguf";
        }

        @Override
        public ArtifactFormat format() {
            return ArtifactFormat.GGUF;
        }

        @Override
        public long sizeBytes() {
            return 1_000_000L;
        }

        @Override
        public io.cortexj.model.Checksum checksum() {
            return null;
        }

        @Override
        public java.util.Map<String, String> metadata() {
            return Collections.singletonMap("quantization", "q4_k");
        }
    };

    private static ResolvedModel testResolvedModel() {
        ModelDescriptor descriptor = ModelDescriptor.builder("e2e/test-model")
                .revision("rev1")
                .architecture("llama")
                .task(TaskCapability.TEXT_GENERATION)
                .trustNote("community upload, checksums unverified")
                .artifact(MODEL_ARTIFACT)
                .build();
        List<ResolvedArtifact> artifacts = new ArrayList<ResolvedArtifact>();
        artifacts.add(new ResolvedArtifact() {
            @Override
            public ModelArtifact artifact() {
                return MODEL_ARTIFACT;
            }

            @Override
            public URI location() {
                return URI.create("file:/tmp/definitely-missing.gguf");
            }

            @Override
            public java.util.Map<String, String> downloadHeaders() {
                return Collections.emptyMap();
            }
        });
        return new ResolvedModel(descriptor, "rev1", RepositoryId.of("e2e-repo"), artifacts);
    }

    static final class FakeRepository implements ModelRepositoryProvider {

        @Override
        public RepositoryId id() {
            return RepositoryId.of("e2e-repo");
        }

        @Override
        public boolean supports(ModelReference reference) {
            return true;
        }

        @Override
        public ResolvedModel resolve(ModelReference reference, ResolutionContext context) {
            return testResolvedModel();
        }
    }

    static final class FakeRuntimeModel implements RuntimeModel, TextGenerationCapability {

        boolean closed;

        @Override
        public ModelCapabilities capabilities() {
            return ModelCapabilities.of(Collections.singleton(TaskCapability.TEXT_GENERATION), true, false, 1);
        }

        @Override
        public InferenceResponse execute(InferenceRequest request) {
            return InferenceResponse.single("executed: " + request.singleInput());
        }

        @Override
        public CompletionStage<InferenceResponse> executeAsync(InferenceRequest request) {
            return CompletableFuture.completedFuture(execute(request));
        }

        @Override
        public GenerationResponse generate(GenerationRequest request) {
            return new GenerationResponse("generated: " + request.prompt(),
                    io.cortexj.inference.FinishReason.STOP,
                    new io.cortexj.inference.TokenUsage(3, 2), null);
        }

        @Override
        public TokenStream stream(GenerationRequest request) {
            return io.cortexj.stream.TokenStreams.single("streamed: " + request.prompt());
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    static final class FakeRuntimeProvider implements RuntimeProvider {

        final RuntimeId id = RuntimeId.of("e2e-runtime");
        final List<FakeRuntimeModel> models = new ArrayList<FakeRuntimeModel>();

        @Override
        public RuntimeId id() {
            return id;
        }

        @Override
        public RuntimeDescriptor descriptor() {
            return RuntimeDescriptor.builder(id)
                    .version("1.0")
                    .format(ArtifactFormat.GGUF)
                    .device(DeviceType.CPU)
                    .task(TaskCapability.TEXT_GENERATION)
                    .maturity(RuntimeDescriptor.Maturity.STABLE)
                    .build();
        }

        @Override
        public RuntimeProbeResult probe(RuntimeProbeContext context) {
            return RuntimeProbeResult.available("e2e runtime ready");
        }

        @Override
        public RuntimeSupport evaluate(RuntimeEvaluationRequest request) {
            if (request.artifact() != null && request.artifact().format() == ArtifactFormat.GGUF) {
                return RuntimeSupport.full(DeviceType.CPU);
            }
            return RuntimeSupport.unsupported();
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
                    FakeRuntimeModel model = new FakeRuntimeModel();
                    models.add(model);
                    return model;
                }

                @Override
                public void close() {
                }
            };
        }
    }

    @Test
    void fullLoadPlanExecuteCloseCycle() {
        FakeRuntimeProvider provider = new FakeRuntimeProvider();
        CortexJ platform = CortexJ.builder()
                .runtimeProvider(provider)
                .repositoryProvider(new FakeRepository())
                .build();
        try {
            ModelHandle handle = platform.models().load(ModelReference.of("e2e/test-model"));
            try {
                assertEquals("e2e/test-model", handle.descriptor().canonicalId());
                assertEquals(ModelLifecycleState.READY, handle.state());
                assertEquals("e2e-runtime", handle.plan().selected().runtimeId());
                assertTrue(handle.supports(TaskCapability.TEXT_GENERATION));

                TextGenerationCapability generator = handle.capability(TextGenerationCapability.class);
                GenerationResponse response = generator.generate(
                        GenerationRequest.builder().prompt("hello").build());
                assertEquals("generated: hello", response.text());
                assertEquals(3, response.usage().inputTokens());

                io.cortexj.inference.InferenceResponse inference = handle instanceof DefaultModelHandle
                        ? ((DefaultModelHandle) handle).execute(InferenceRequest.builder().input("probe").build())
                        : null;
                assertEquals("executed: probe", inference.singleOutput());
            } finally {
                handle.close();
            }
            DefaultModelHandle second = (DefaultModelHandle) platform.models()
                    .load(ExecutionRequest.forModel(ModelReference.of("e2e/test-model")));
            assertEquals(ModelLifecycleState.READY, second.state());
            second.close();
            assertEquals(ModelLifecycleState.CLOSED, second.state());

            io.cortexj.diagnostics.SupportReport report = platform.support()
                    .inspect(ModelReference.of("e2e/test-model"));
            assertTrue(report.isExecutable());
        } finally {
            platform.close();
        }
    }

    @Test
    void unsupportedCapabilityThrowsTypedError() {
        CortexJ platform = CortexJ.builder()
                .runtimeProvider(new FakeRuntimeProvider())
                .repositoryProvider(new FakeRepository())
                .build();
        try {
            ModelHandle handle = platform.models().load(ModelReference.of("e2e/test-model"));
            try {
                io.cortexj.error.CortexJException failure = assertThrows(io.cortexj.error.CortexJException.class,
                        () -> handle.capability(EmbeddingCapability.class));
                assertEquals("CORTEXJ-INFER-9004", failure.code());
            } finally {
                handle.close();
            }
        } finally {
            platform.close();
        }
    }
}
