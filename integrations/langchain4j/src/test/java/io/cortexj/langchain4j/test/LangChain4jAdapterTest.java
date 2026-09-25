package io.cortexj.langchain4j.test;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.Response;
import io.cortexj.CortexJ;
import io.cortexj.inference.EmbeddingCapability;
import io.cortexj.inference.FinishReason;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.GenerationResponse;
import io.cortexj.inference.TextGenerationCapability;
import io.cortexj.inference.TokenUsage;
import io.cortexj.langchain4j.CortexJChatModel;
import io.cortexj.langchain4j.CortexJEmbeddingModel;
import io.cortexj.langchain4j.CortexJStreamingChatModel;
import io.cortexj.Models;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelReference;
import io.cortexj.stream.StreamCompletion;
import io.cortexj.stream.TokenConsumer;
import io.cortexj.stream.TokenStream;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangChain4jAdapterTest {

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
    void chatModelMapsRequestToAiMessage() {
        ModelHandle handle = fakeHandle(new TextGenerationCapability() {
            @Override
            public GenerationResponse generate(GenerationRequest request) {
                return new GenerationResponse("hello!", FinishReason.STOP, new TokenUsage(3L, 5L), Map.of());
            }

            @Override
            public TokenStream stream(GenerationRequest request) {
                throw new UnsupportedOperationException();
            }
        }, null);
        CortexJChatModel model = new CortexJChatModel(fakePlatform(handle), "test/model");

        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(UserMessage.from("hi"))
                .build());
        assertEquals("hello!", response.aiMessage().text());
        assertEquals("test/model", model.modelName());
        assertTrue(response.metadata().tokenUsage().inputTokenCount() > 0);
    }

    @Test
    void streamingModelForwardsPartialResponses() throws Exception {
        ModelHandle handle = fakeHandle(new TextGenerationCapability() {
            @Override
            public GenerationResponse generate(GenerationRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public TokenStream stream(GenerationRequest request) {
                io.cortexj.stream.internal.PushTokenStream stream =
                        new io.cortexj.stream.internal.PushTokenStream();
                new Thread(() -> {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                    stream.emit("Hel", Map.of());
                    stream.emit("lo", Map.of());
                    stream.complete(FinishReason.STOP, new TokenUsage(1L, 2L));
                }).start();
                return stream;
            }
        }, null);
        CortexJStreamingChatModel model = new CortexJStreamingChatModel(fakePlatform(handle), "test/model");

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> partials = new AtomicReference<>("");
        AtomicReference<String> complete = new AtomicReference<>();
        model.chat(ChatRequest.builder().messages(UserMessage.from("hi")).build(),
                new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String token) {
                        partials.updateAndGet(value -> value + token);
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse response) {
                        complete.set(response.aiMessage().text());
                        done.countDown();
                    }

                    @Override
                    public void onError(Throwable error) {
                        done.countDown();
                    }
                });
        assertTrue(done.await(10, TimeUnit.SECONDS), "streaming did not complete in time");
        assertEquals("Hello", partials.get());
        assertEquals("Hello", complete.get());
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

        Response<List<dev.langchain4j.data.embedding.Embedding>> response =
                model.embedAll(List.of(TextSegment.from("a"), TextSegment.from("b")));
        assertEquals(2, response.content().size());
        assertEquals(2f, response.content().get(1).vector()[0]);
        assertEquals(3, model.embed("hello").content().vector().length);
    }
}
