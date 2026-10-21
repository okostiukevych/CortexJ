package io.cortexj.springai.test;

import io.cortexj.CortexJ;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.GenerationResponse;
import io.cortexj.inference.TextGenerationCapability;
import io.cortexj.inference.TokenUsage;
import io.cortexj.inference.FinishReason;
import io.cortexj.model.ChatRole;
import io.cortexj.model.ModelCapabilities;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelLifecycleState;
import io.cortexj.model.ModelReference;
import io.cortexj.model.TaskCapability;
import io.cortexj.Models;
import io.cortexj.springai.CortexJChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tool calling THROUGH the Spring AI adapter (audit 2026-10-21 follow-up):
 * the model proposes a call, the adapter hands a Spring ChatResponse with the
 * tool calls to Spring's {@link ToolCallingManager}, which executes the
 * caller-supplied {@code ToolCallback}, and the adapter drives the next round
 * with the conversation history the manager produced. Spring runs the tool
 * loop ABOVE the model, unchanged.
 */
class SpringAiToolLoopTest {

    @Test
    void springToolCallbacksRunAgainstCortexJModelProposals() {
        final AtomicReference<String> executedArguments = new AtomicReference<String>();
        FunctionToolCallback<java.util.Map<String, Object>, String> callback = FunctionToolCallback
                .builder("get_weather", (java.util.Map<String, Object> arguments) -> {
                    executedArguments.set(String.valueOf(arguments));
                    return "The weather is -3C";
                })
                .inputSchema("{\"type\":\"object\"}")
                .inputType(java.util.Map.class)
                .build();

        final List<GenerationRequest> requests = new ArrayList<GenerationRequest>();
        ModelHandle handle = scriptedHandle(requests,
                new GenerationResponse("", FinishReason.TOOL_CALL, TokenUsage.unknown(),
                        java.util.Map.of(),
                        java.util.List.of(new io.cortexj.model.ToolCall("call_1", "get_weather",
                                "{\"city\":\"Omsk\"}"))),
                () -> new GenerationResponse("The weather is -3", FinishReason.STOP,
                        TokenUsage.unknown(), java.util.Map.of()));

        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(callback)
                .build();
        CortexJChatModel model = new CortexJChatModel(fakePlatform(handle), "test/model");
        try {
            var chatResponse = model.call(new Prompt("weather in Omsk?", (ChatOptions) options));

            assertEquals("The weather is -3", chatResponse.getResult().getOutput().getText());
            assertEquals("{city=Omsk}", executedArguments.get());
            // round 2 must carry the loop state: the original user message, the
            // assistant echo with the proposed calls, and the tool result tied
            // to its call id
            GenerationRequest second = requests.get(1);
            assertEquals(3, second.messages().size());
            assertEquals(ChatRole.ASSISTANT, second.messages().get(1).role());
            assertEquals("get_weather", second.messages().get(1).toolCalls().get(0).name());
            assertEquals(ChatRole.TOOL, second.messages().get(2).role());
            assertEquals("call_1", second.messages().get(2).toolCallId());
            // the tool result arrives through Spring's JSON result converter
            assertEquals("\"The weather is -3C\"", second.messages().get(2).content());
            // declared tools ride the request both rounds
            assertEquals("get_weather", second.tools().get(0).name());
        } finally {
            model.close();
        }
    }

    @Test
    void streamingWithToolsRunsTheLoopInlineAndEmitsTheFinalAnswer() {
        final List<GenerationRequest> requests = new ArrayList<GenerationRequest>();
        ModelHandle handle = scriptedHandle(requests,
                new GenerationResponse("", FinishReason.TOOL_CALL, TokenUsage.unknown(),
                        java.util.Map.of(),
                        java.util.List.of(new io.cortexj.model.ToolCall("call_1", "get_weather",
                                "{\"city\":\"Omsk\"}"))),
                () -> new GenerationResponse("The weather is -3C", FinishReason.STOP,
                        TokenUsage.unknown(), java.util.Map.of()));

        FunctionToolCallback<java.util.Map<String, Object>, String> callback = FunctionToolCallback
                .builder("get_weather", (java.util.Map<String, Object> arguments) -> "The weather is -3C")
                .inputSchema("{\"type\":\"object\"}")
                .inputType(java.util.Map.class)
                .build();
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(callback)
                .build();
        io.cortexj.springai.CortexJStreamingChatModel model =
                new io.cortexj.springai.CortexJStreamingChatModel(fakePlatform(handle), "test/model");
        try {
            java.util.List<org.springframework.ai.chat.model.ChatResponse> chunks =
                    model.stream(new Prompt("weather in Omsk?", (ChatOptions) options))
                            .collectList()
                            .block(java.time.Duration.ofSeconds(10));

            assertEquals(1, chunks.size());
            assertEquals("The weather is -3C", chunks.get(0).getResult().getOutput().getText());
            // streamed loop reached round 2 with exactly the same state as the blocking path
            assertEquals(3, requests.get(1).messages().size());
            assertEquals(ChatRole.TOOL, requests.get(1).messages().get(2).role());
        } finally {
            model.close();
        }
    }

    // ---- fake platform, model handle with a scripted generator ----

    private interface AnswerSupplier {
        GenerationResponse answer();
    }

    private static ModelHandle scriptedHandle(List<GenerationRequest> requests,
                                              GenerationResponse first,
                                              AnswerSupplier rest) {
        Deque<GenerationResponse> responses = new ArrayDeque<GenerationResponse>(
                java.util.Collections.singletonList(first));
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
                            requests.add(request);
                            GenerationResponse next = responses.poll();
                            return next != null ? next : rest.answer();
                        }

                        @Override
                        public io.cortexj.stream.TokenStream stream(GenerationRequest request) {
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
}
