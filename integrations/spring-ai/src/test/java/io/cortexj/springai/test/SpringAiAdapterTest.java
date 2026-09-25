package io.cortexj.springai.test;

import io.cortexj.CortexJ;
import io.cortexj.inference.EmbeddingCapability;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.GenerationResponse;
import io.cortexj.stream.StreamCompletion;
import io.cortexj.inference.TextGenerationCapability;
import io.cortexj.inference.TokenUsage;
import io.cortexj.inference.FinishReason;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.model.ModelHandle;
import io.cortexj.Models;
import io.cortexj.model.ModelReference;
import io.cortexj.springai.CortexJChatModel;
import io.cortexj.springai.CortexJEmbeddingModel;
import io.cortexj.springai.CortexJStreamingChatModel;
import io.cortexj.stream.Token;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingRequest;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpringAiAdapterTest {

    private static CortexJ fakePlatform(final ModelHandle handle) {
        return new CortexJ() {
            @Override
            public Models models() {
                return new Models() {
                    @Override
                    public ModelHandle load(ModelReference reference) {
                        return handle;
                    }

                    @Override
                    public ModelHandle load(io.cortexj.planner.ExecutionRequest request) {
                        return handle;
                    }
                };
            }

            @Override
            public io.cortexj.planner.Planner planner() {
                return null;
            }

            @Override
            public io.cortexj.diagnostics.Support support() {
                return null;
            }

            @Override
            public io.cortexj.hardware.EnvironmentProfile environment() {
                return null;
            }

            @Override
            public io.cortexj.hardware.HardwareProfile hardware() {
                return null;
            }

            @Override
            public List<io.cortexj.runtime.RuntimeDescriptor> runtimes() {
                return List.of();
            }

            @Override
            public List<io.cortexj.runtime.RuntimeHealthReport> runtimeHealth() {
                return List.of();
            }

            @Override
            public String platformModule() {
                return "cortexj-core";
            }

            @Override
            public void close() {
            }
        };
    }

    private static ModelHandle fakeHandle(final TextGenerationCapability generator,
                                          final EmbeddingCapability embedding) {
        return new ModelHandle() {
            @Override
            public ModelDescriptor descriptor() {
                return ModelDescriptor.builder("test/model").build();
            }

            @Override
            public io.cortexj.planner.ExecutionPlan plan() {
                return null;
            }

            @Override
            public io.cortexj.model.ModelCapabilities capabilities() {
                return null;
            }

            @Override
            public <T> T capability(Class<T> capabilityType) {
                if (capabilityType == TextGenerationCapability.class) {
                    return capabilityType.cast(generator);
                }
                if (capabilityType == EmbeddingCapability.class) {
                    return capabilityType.cast(embedding);
                }
                throw new IllegalArgumentException("no capability " + capabilityType);
            }

            @Override
            public <T> java.util.Optional<T> capabilityIfSupported(Class<T> capabilityType) {
                return java.util.Optional.empty();
            }

            @Override
            public boolean supports(io.cortexj.model.TaskCapability task) {
                return false;
            }

            @Override
            public io.cortexj.model.ModelLifecycleState state() {
                return io.cortexj.model.ModelLifecycleState.READY;
            }

            @Override
            public void waitForReady() {
            }

            @Override
            public void close() {
            }
        };
    }

    @Test
    void chatModelMapsPromptToGenerationResponse() {
        ModelHandle handle = fakeHandle(new TextGenerationCapability() {
            @Override
            public GenerationResponse generate(GenerationRequest request) {
                return new GenerationResponse("hello!", FinishReason.STOP, new TokenUsage(1L, 1L),
                        java.util.Map.of());
            }

            @Override
            public io.cortexj.stream.TokenStream stream(GenerationRequest request) {
                throw new UnsupportedOperationException();
            }
        }, null);
        CortexJChatModel model = new CortexJChatModel(fakePlatform(handle), "test/model");

        org.springframework.ai.chat.model.ChatResponse response =
                model.call(new Prompt(List.of(new UserMessage("hi"))));
        assertEquals("hello!", response.getResult().getOutput().getText());
        assertEquals("test/model", model.getModelName());
    }

    @Test
    void streamingModelEmitsTokenChunks() {
        ModelHandle handle = fakeHandle(new TextGenerationCapability() {
            @Override
            public GenerationResponse generate(GenerationRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public io.cortexj.stream.TokenStream stream(GenerationRequest request) {
                io.cortexj.stream.internal.PushTokenStream stream =
                        new io.cortexj.stream.internal.PushTokenStream();
                new Thread(() -> {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                    stream.emit("Hel", java.util.Map.of());
                    stream.emit("lo", java.util.Map.of());
                    stream.complete(FinishReason.STOP, new TokenUsage(1L, 2L));
                }).start();
                return stream;
            }
        }, null);
        CortexJStreamingChatModel model = new CortexJStreamingChatModel(fakePlatform(handle), "test/model");

        String collected = model.stream(new Prompt(List.of(new UserMessage("hi"))))
                .map(response -> response.getResult().getOutput().getText())
                .reduce("", String::concat)
                .block();
        assertEquals("Hello", collected);
    }

    @Test
    void embeddingModelMapsVectors() {
        ModelHandle handle = fakeHandle(null, new EmbeddingCapability() {
            @Override
            public io.cortexj.inference.EmbeddingResponse embed(io.cortexj.inference.EmbeddingRequest request) {
                if (request.texts().size() == 1) {
                    return io.cortexj.inference.EmbeddingResponse.single(new float[]{0.1f, 0.2f, 0.3f});
                }
                return new io.cortexj.inference.EmbeddingResponse(List.of(new float[]{1f}, new float[]{2f}));
            }
        });
        CortexJEmbeddingModel model = new CortexJEmbeddingModel(fakePlatform(handle), "test/model");

        assertEquals(3, model.embed("hello").length);
        assertEquals(2, model.embed(List.of("a", "b")).size());
        assertEquals(3, model.embed(new Document("hello")).length);
        org.springframework.ai.embedding.EmbeddingResponse response =
                model.call(new org.springframework.ai.embedding.EmbeddingRequest(List.of("a", "b"),
                        org.springframework.ai.embedding.EmbeddingOptionsBuilder.builder().build()));
        assertEquals(2, response.getResults().size());
    }
}
