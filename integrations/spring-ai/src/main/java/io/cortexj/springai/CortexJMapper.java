package io.cortexj.springai;

import io.cortexj.inference.FinishReason;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.GenerationResponse;
import io.cortexj.inference.TokenUsage;
import io.cortexj.model.ChatMessage;
import io.cortexj.model.ChatRole;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

/**
 * Translates between the Spring AI {@link Prompt}/{@link ChatResponse} model and the
 * CortexJ inference model. Shared by the blocking and streaming adapters so both
 * paths behave identically.
 *
 * <p>Honesty: CortexJ's {@link ChatMessage} is text-only and the generation API has no
 * tool-call concept yet, so Spring AI tool-call requests (assistant {@code toolCalls})
 * are not executable through this adapter - the loop above the model (Spring AI 2.0
 * {@code ToolCallingAdvisor}) never sees a tool-call response it could act on.
 * Multi-turn messages, system messages and generation options ARE mapped.</p>
 */
final class CortexJMapper {

    private CortexJMapper() {
    }

    /**
     * Maps a Spring AI prompt (messages + options) to a CortexJ generation request.
     *
     * <p>Role mapping: SYSTEM -> {@link ChatRole#SYSTEM}, USER -> {@link ChatRole#USER},
     * ASSISTANT -> {@link ChatRole#ASSISTANT}, TOOL -> {@link ChatRole#TOOL}. Assistant
     * tool-call requests are flattened to their text content and media content is
     * dropped (no chat multimodal input in the runtime API).</p>
     */
    static GenerationRequest generationRequest(Prompt prompt) {
        GenerationRequest.Builder builder = GenerationRequest.builder();
        List<Message> instructions = prompt.getInstructions();
        if (instructions == null || instructions.isEmpty()) {
            builder.prompt(orEmpty(prompt.getContents()));
        } else {
            for (Message message : instructions) {
                for (ChatMessage chatMessage : toChatMessages(message)) {
                    builder.message(chatMessage);
                }
            }
        }
        applyOptions(prompt.getOptions(), builder);
        appendTools(prompt.getOptions(), builder);
        return builder.build();
    }

    private static java.util.List<ChatMessage> toChatMessages(Message message) {
        // TOOL results fan out: each response is its own TOOL message tied to
        // the call it answers by id (the agentic loop pairs them)
        if (message instanceof ToolResponseMessage toolMessage) {
            java.util.List<ChatMessage> mapped = new java.util.ArrayList<ChatMessage>();
            if (toolMessage.getResponses() != null) {
                for (ToolResponseMessage.ToolResponse response : toolMessage.getResponses()) {
                    String data = response.responseData() == null ? "" : response.responseData();
                    if (response.id() != null && !response.id().isEmpty()) {
                        mapped.add(ChatMessage.tool(response.id(), data));
                    } else {
                        mapped.add(ChatMessage.of(ChatRole.TOOL, data));
                    }
                }
            }
            return mapped;
        }
        String text = message.getText() == null ? "" : message.getText();
        MessageType type = message.getMessageType() == null ? MessageType.USER : message.getMessageType();
        if (type == MessageType.SYSTEM) {
            return java.util.List.of(ChatMessage.system(text));
        }
        if (type == MessageType.ASSISTANT) {
            if (message instanceof AssistantMessage && ((AssistantMessage) message).hasToolCalls()) {
                return java.util.List.of(ChatMessage.assistantToolCalls(
                        toolCalls(((AssistantMessage) message).getToolCalls()), text));
            }
            return java.util.List.of(ChatMessage.assistant(text));
        }
        if (type == MessageType.TOOL) {
            return java.util.List.of(ChatMessage.of(ChatRole.TOOL, text));
        }
        return java.util.List.of(ChatMessage.user(text));
    }

    private static java.util.List<io.cortexj.model.ToolCall> toolCalls(
            List<AssistantMessage.ToolCall> toolCalls) {
        java.util.List<io.cortexj.model.ToolCall> mapped = new java.util.ArrayList<io.cortexj.model.ToolCall>();
        if (toolCalls != null) {
            for (AssistantMessage.ToolCall call : toolCalls) {
                mapped.add(new io.cortexj.model.ToolCall(call.id(), call.name(), call.arguments()));
            }
        }
        return mapped;
    }

    /** ToolCallingChatOptions callbacks -> declared tools for the runtime. */
    private static void appendTools(ChatOptions options, GenerationRequest.Builder builder) {
        if (options instanceof org.springframework.ai.model.tool.ToolCallingChatOptions toolOptions) {
            List<org.springframework.ai.tool.ToolCallback> callbacks = toolOptions.getToolCallbacks();
            if (callbacks != null) {
                for (org.springframework.ai.tool.ToolCallback callback : callbacks) {
                    org.springframework.ai.tool.definition.ToolDefinition definition =
                            callback.getToolDefinition();
                    if (definition != null) {
                        builder.tool(io.cortexj.model.ToolDefinition.function(
                                definition.name(),
                                definition.description(),
                                definition.inputSchema()));
                    }
                }
            }
        }
    }

    private static void applyOptions(ChatOptions options, GenerationRequest.Builder builder) {
        if (options == null) {
            return;
        }
        if (options.getTemperature() != null) {
            builder.temperature(options.getTemperature());
        }
        if (options.getMaxTokens() != null) {
            builder.maxTokens(options.getMaxTokens());
        }
        if (options.getTopP() != null) {
            builder.topP(options.getTopP());
        }
        if (options.getTopK() != null) {
            builder.topK(options.getTopK());
        }
        List<String> stopSequences = options.getStopSequences();
        if (stopSequences != null) {
            for (String stop : stopSequences) {
                builder.stopSequence(stop);
            }
        }
    }

    /** Builds a Generation with finish-reason metadata attached to it. */
    static Generation generation(String text, FinishReason finishReason) {
        return generation(text, finishReason, java.util.List.of());
    }

    /**
     * Builds a Generation whose assistant output carries the tool calls the
     * model proposed (Spring's {@code ToolCallingManager} reads them from
     * here to execute the caller's {@code ToolCallback}s).
     */
    static Generation generation(String text, FinishReason finishReason,
                                 java.util.List<io.cortexj.model.ToolCall> toolCalls) {
        AssistantMessage.Builder<?> output = AssistantMessage.builder().content(orEmpty(text));
        if (toolCalls != null && !toolCalls.isEmpty()) {
            List<AssistantMessage.ToolCall> records = new java.util.ArrayList<AssistantMessage.ToolCall>();
            for (io.cortexj.model.ToolCall call : toolCalls) {
                records.add(new AssistantMessage.ToolCall(call.id(), "function", call.name(), call.arguments()));
            }
            output.toolCalls(records);
        }
        ChatGenerationMetadata metadata = ChatGenerationMetadata.builder()
                .finishReason(finishReasonValue(finishReason))
                .build();
        return new Generation(output.build(), metadata);
    }

    /** Full response: one generation + metadata, used by the blocking adapter. */
    static ChatResponse chatResponse(GenerationResponse response, String model) {
        Generation generation = generation(response.text(), response.finishReason(), response.toolCalls());
        return new ChatResponse(java.util.List.of(generation),
                metadata(model, response.usage(), response.metadata()));
    }

    /** Builds response metadata: model id, token usage and platform event metadata. */
    static ChatResponseMetadata metadata(String model, TokenUsage usage, Map<String, String> platformMetadata) {        ChatResponseMetadata.Builder builder = ChatResponseMetadata.builder()
                .model(model == null ? "" : model);
        if (usage != null && usage.isKnown()) {
            builder.usage(new CortexJUsage(usage));
        }
        if (platformMetadata != null) {
            for (Map.Entry<String, String> entry : platformMetadata.entrySet()) {
                builder.keyValue(entry.getKey(), entry.getValue());
            }
        }
        return builder.build();
    }

    /** OpenAI-conventional finish-reason strings so downstream logic recognizes them. */
    static String finishReasonValue(FinishReason finishReason) {
        if (finishReason == null) {
            return "unknown";
        }
        switch (finishReason) {
            case STOP:
                return "stop";
            case LENGTH:
                return "length";
            case TOOL_CALL:
                return "tool_calls";
            case CONTENT_FILTER:
                return "content_filter";
            default:
                return "unknown";
        }
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * Adapts CortexJ {@link TokenUsage} to the Spring AI {@link Usage} contract.
     * Unknown counts are reported as {@code null} (never as zeros or negatives).
     */
    private static final class CortexJUsage implements Usage {

        private final TokenUsage usage;

        CortexJUsage(TokenUsage usage) {
            this.usage = usage;
        }

        @Override
        public Integer getPromptTokens() {
            return usage.inputTokens() < 0 ? null : (int) usage.inputTokens();
        }

        @Override
        public Integer getCompletionTokens() {
            return usage.outputTokens() < 0 ? null : (int) usage.outputTokens();
        }

        @Override
        public Integer getTotalTokens() {
            long total = usage.totalTokens();
            return total < 0 ? null : (int) total;
        }

        @Override
        public Object getNativeUsage() {
            return usage;
        }
    }
}
