package io.cortexj.springai.test;

import io.cortexj.CortexJ;
import io.cortexj.inference.EmbeddingCapability;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.GenerationResponse;
import io.cortexj.inference.TextGenerationCapability;
import io.cortexj.inference.TokenUsage;
import io.cortexj.inference.FinishReason;
import io.cortexj.model.ChatMessage;
import io.cortexj.model.ChatRole;
import io.cortexj.model.ModelCapabilities;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelLifecycleState;
import io.cortexj.model.ModelReference;
import io.cortexj.model.TaskCapability;
import io.cortexj.Models;
import io.cortexj.springai.CortexJChatModel;
import io.cortexj.springai.CortexJEmbeddingModel;
import io.cortexj.springai.CortexJStreamingChatModel;
import io.cortexj.stream.TokenStream;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

                    @Override
                    public java.util.concurrent.Future<ModelHandle> loadAsync(ModelReference reference) {
                        return java.util.concurrent.CompletableFuture.completedFuture(handle);
                    }

                    @Override
                    public java.util.concurrent.Future<ModelHandle> loadAsync(io.cortexj.planner.ExecutionRequest request) {
                        return java.util.concurrent.CompletableFuture.completedFuture(handle);
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

    /**
     * Handle with a text generator that records the generation request and answers
     * from the supplier with the given token usage.
     */
    private static ModelHandle textHandle(final AtomicReference<GenerationRequest> captured,
                                          final TokenUsage usage,
                                          final String answer) {
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
            public ModelCapabilities capabilities() {
                return null;
            }

            @Override
            public <T> T capability(Class<T> capabilityType) {
                if (capabilityType == TextGenerationCapability.class) {
                    return capabilityType.cast(new TextGenerationCapability() {
                        @Override
                        public GenerationResponse generate(GenerationRequest request) {
                            if (captured != null) {
                                captured.set(request);
                            }
                            return new GenerationResponse(answer, FinishReason.STOP, usage, java.util.Map.of());
                        }

                        @Override
                        public TokenStream stream(GenerationRequest request) {
                            throw new UnsupportedOperationException();
                        }
                    });
                }
                throw new IllegalArgumentException("no capability " + capabilityType);
            }

            @Override
            public <T> java.util.Optional<T> capabilityIfSupported(Class<T> capabilityType) {
                return java.util.Optional.empty();
            }

            @Override
            public boolean supports(TaskCapability task) {
                return false;
            }

            @Override
            public ModelLifecycleState state() {
                return ModelLifecycleState.READY;
            }

            @Override
            public void waitForReady() {
            }

            @Override
            public void close() {
            }
        };
    }

    private static ModelHandle streamingHandle(final TokenStream tokenStream) {
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
            public ModelCapabilities capabilities() {
                return null;
            }

            @Override
            public <T> T capability(Class<T> capabilityType) {
                if (capabilityType == TextGenerationCapability.class) {
                    return capabilityType.cast(new TextGenerationCapability() {
                        @Override
                        public GenerationResponse generate(GenerationRequest request) {
                            throw new UnsupportedOperationException();
                        }

                        @Override
                        public TokenStream stream(GenerationRequest request) {
                            return tokenStream;
                        }
                    });
                }
                throw new IllegalArgumentException("no capability " + capabilityType);
            }

            @Override
            public <T> java.util.Optional<T> capabilityIfSupported(Class<T> capabilityType) {
                return java.util.Optional.empty();
            }

            @Override
            public boolean supports(TaskCapability task) {
                return false;
            }

            @Override
            public ModelLifecycleState state() {
                return ModelLifecycleState.READY;
            }

            @Override
            public void waitForReady() {
            }

            @Override
            public void close() {
            }
        };
    }

    private static ModelHandle embeddingHandle(final EmbeddingCapability embedding) {
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
            public ModelCapabilities capabilities() {
                return null;
            }

            @Override
            public <T> T capability(Class<T> capabilityType) {
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
            public boolean supports(TaskCapability task) {
                return false;
            }

            @Override
            public ModelLifecycleState state() {
                return ModelLifecycleState.READY;
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
        AtomicReference<GenerationRequest> captured = new AtomicReference<GenerationRequest>();
        ModelHandle handle = textHandle(captured, new TokenUsage(1L, 1L), "hello!");
        CortexJChatModel model = new CortexJChatModel(fakePlatform(handle), "test/model");

        org.springframework.ai.chat.model.ChatResponse response =
                model.call(new Prompt(List.of(new UserMessage("hi"))));

        assertEquals("hello!", response.getResult().getOutput().getText());
        assertEquals("stop", response.getResult().getMetadata().getFinishReason());
        assertEquals("test/model", response.getMetadata().getModel());
        assertEquals("test/model", model.getModelName());
        assertEquals(Integer.valueOf(1), response.getMetadata().getUsage().getPromptTokens());
        assertEquals(Integer.valueOf(1), response.getMetadata().getUsage().getCompletionTokens());
        assertEquals(Integer.valueOf(2), response.getMetadata().getUsage().getTotalTokens());
    }

    @Test
    void unknownUsageIsOmittedFromMetadata() {
        ModelHandle handle = textHandle(null, TokenUsage.unknown(), "ok");
        CortexJChatModel model = new CortexJChatModel(fakePlatform(handle), "test/model");

        org.springframework.ai.chat.model.ChatResponse response =
                model.call(new Prompt(List.of(new UserMessage("hi"))));

        assertEquals("ok", response.getResult().getOutput().getText());
        // unset usage surfaces as Spring AI's EmptyUsage (zeros), not as a fake count
        assertEquals(Integer.valueOf(0), response.getMetadata().getUsage().getPromptTokens());
        assertEquals(Integer.valueOf(0), response.getMetadata().getUsage().getCompletionTokens());
    }

    @Test
    void multiTurnMessagesKeepRolesAndOrder() {
        AtomicReference<GenerationRequest> captured = new AtomicReference<GenerationRequest>();
        ModelHandle handle = textHandle(captured, TokenUsage.unknown(), "reply");
        CortexJChatModel model = new CortexJChatModel(fakePlatform(handle), "test/model");

        model.call(new Prompt(List.of(
                new SystemMessage("You are terse."),
                new UserMessage("Question?"),
                new AssistantMessage("Answer."),
                new UserMessage("Follow up"))));

        GenerationRequest request = captured.get();
        List<ChatMessage> messages = request.messages();
        assertEquals(4, messages.size());
        assertEquals(ChatRole.SYSTEM, messages.get(0).role());
        assertEquals("You are terse.", messages.get(0).content());
        assertEquals(ChatRole.USER, messages.get(1).role());
        assertEquals("Question?", messages.get(1).content());
        assertEquals(ChatRole.ASSISTANT, messages.get(2).role());
        assertEquals("Answer.", messages.get(2).content());
        assertEquals(ChatRole.USER, messages.get(3).role());
        assertEquals("Follow up", messages.get(3).content());
    }

    @Test
    void toolResponseMapsToToolRole() {
        AtomicReference<GenerationRequest> captured = new AtomicReference<GenerationRequest>();
        ModelHandle handle = textHandle(captured, TokenUsage.unknown(), "reply");
        CortexJChatModel model = new CortexJChatModel(fakePlatform(handle), "test/model");

        org.springframework.ai.chat.messages.ToolResponseMessage tool = org.springframework.ai.chat.messages.ToolResponseMessage
                .builder()
                .responses(List.of(new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(
                        "call-1", "lookup", "{\"ok\":true}")))
                .build();
        model.call(new Prompt(List.of(new UserMessage("use the tool"), tool)));

        List<ChatMessage> messages = captured.get().messages();
        assertEquals(2, messages.size());
        assertEquals(ChatRole.TOOL, messages.get(1).role());
        assertEquals("{\"ok\":true}", messages.get(1).content());
    }

    @Test
    void chatOptionsMapToGenerationRequest() {
        AtomicReference<GenerationRequest> captured = new AtomicReference<GenerationRequest>();
        ModelHandle handle = textHandle(captured, TokenUsage.unknown(), "ok");
        CortexJChatModel model = new CortexJChatModel(fakePlatform(handle), "test/model");

        ChatOptions options = ChatOptions.builder()
                .temperature(0.7)
                .maxTokens(128)
                .topP(0.9)
                .topK(40)
                .stopSequences(List.of("END", "STOP"))
                .build();
        model.call(new Prompt(List.of(new UserMessage("hi")), options));

        GenerationRequest request = captured.get();
        assertEquals(Double.valueOf(0.7), request.temperature());
        assertEquals(Integer.valueOf(128), request.maxTokens());
        assertEquals(Double.valueOf(0.9), request.topP());
        assertEquals(Integer.valueOf(40), request.topK());
        assertEquals(List.of("END", "STOP"), request.stopSequences());
    }

    @Test
    void singleStringBecomesUserMessage() {
        AtomicReference<GenerationRequest> captured = new AtomicReference<GenerationRequest>();
        ModelHandle handle = textHandle(captured, TokenUsage.unknown(), "ok");
        CortexJChatModel model = new CortexJChatModel(fakePlatform(handle), "test/model");

        model.call(new Prompt(List.of(new UserMessage("plain"))));

        GenerationRequest request = captured.get();
        assertTrue(request.isChat());
        assertTrue(request.prompt().isEmpty());
        assertEquals(ChatRole.USER, request.messages().get(0).role());
        assertEquals("plain", request.messages().get(0).content());
    }

    @Test
    void chatModelExposesResolvedModelInOptions() {
        ModelHandle handle = textHandle(null, TokenUsage.unknown(), "ok");
        CortexJChatModel model = new CortexJChatModel(fakePlatform(handle), "test/model");

        assertEquals("test/model", model.getOptions().getModel());
    }

    @Test
    void streamingConcatenatesTextAndFinishesWithUsageChunk() {
        ModelHandle handle = streamingHandle(emitHelloStream());
        CortexJChatModel model = new CortexJChatModel(fakePlatform(handle), "test/model");

        List<org.springframework.ai.chat.model.ChatResponse> responses =
                model.stream(new Prompt(List.of(new UserMessage("hi")))).collectList().block();

        assertEquals(3, responses.size());
        assertEquals("Hel", responses.get(0).getResult().getOutput().getText());
        assertEquals("lo", responses.get(1).getResult().getOutput().getText());

        org.springframework.ai.chat.model.ChatResponse last = responses.get(2);
        assertEquals("", last.getResult().getOutput().getText());
        assertEquals("stop", last.getResult().getMetadata().getFinishReason());
        assertEquals("test/model", last.getMetadata().getModel());
        assertEquals(Integer.valueOf(2), last.getMetadata().getUsage().getCompletionTokens());
    }

    @Test
    void streamingOnlyModelDelegatesToSameContract() {
        ModelHandle handle = streamingHandle(emitHelloStream());
        CortexJStreamingChatModel model = new CortexJStreamingChatModel(fakePlatform(handle), "test/model");

        String collected = model.stream(new Prompt(List.of(new UserMessage("hi"))))
                .map(response -> response.getResult().getOutput().getText())
                .reduce("", String::concat)
                .block();
        assertEquals("Hello", collected);
        assertEquals("test/model", model.getModelName());
    }

    private static TokenStream emitHelloStream() {
        io.cortexj.stream.internal.PushTokenStream stream = new io.cortexj.stream.internal.PushTokenStream();
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

    @Test
    void embeddingModelMapsVectors() {
        ModelHandle handle = embeddingHandle(new EmbeddingCapability() {
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
                        EmbeddingOptions.builder().build()));
        assertEquals(2, response.getResults().size());
    }
}
