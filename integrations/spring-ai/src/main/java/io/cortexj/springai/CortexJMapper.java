package io.cortexj.springai;

import io.cortexj.inference.FinishReason;
import io.cortexj.inference.GenerationRequest;
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
                builder.message(toChatMessage(message));
            }
        }
        applyOptions(prompt.getOptions(), builder);
        return builder.build();
    }

    private static ChatMessage toChatMessage(Message message) {
        if (message instanceof ToolResponseMessage) {
            // tool results carry their payloads in the responses, not in the message text
            return ChatMessage.of(ChatRole.TOOL, toolResponsesContent((ToolResponseMessage) message));
        }
        String text = message.getText() == null ? "" : message.getText();
        MessageType type = message.getMessageType() == null ? MessageType.USER : message.getMessageType();
        if (type == MessageType.SYSTEM) {
            return ChatMessage.system(text);
        }
        if (type == MessageType.ASSISTANT) {
            return ChatMessage.assistant(text);
        }
        if (type == MessageType.TOOL) {
            return ChatMessage.of(ChatRole.TOOL, text);
        }
        return ChatMessage.user(text);
    }

    /** OpenAI-style tool result content: each response's data, joined in order. */
    private static String toolResponsesContent(ToolResponseMessage message) {
        StringBuilder content = new StringBuilder();
        if (message.getResponses() != null) {
            for (ToolResponseMessage.ToolResponse response : message.getResponses()) {
                if (content.length() > 0) {
                    content.append('\n');
                }
                if (response.responseData() != null) {
                    content.append(response.responseData());
                }
            }
        }
        return content.toString();
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
        AssistantMessage output = new AssistantMessage(text == null ? "" : text);
        ChatGenerationMetadata metadata = ChatGenerationMetadata.builder()
                .finishReason(finishReasonValue(finishReason))
                .build();
        return new Generation(output, metadata);
    }

    /** Builds response metadata: model id, token usage and platform event metadata. */
    static ChatResponseMetadata metadata(String model, TokenUsage usage, Map<String, String> platformMetadata) {
        ChatResponseMetadata.Builder builder = ChatResponseMetadata.builder()
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
