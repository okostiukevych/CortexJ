package io.cortexj.springai;

import io.cortexj.inference.FinishReason;
import io.cortexj.inference.GenerationResponse;
import io.cortexj.inference.TextGenerationCapability;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;

/**
 * Agentic round driver shared by the blocking and streaming Spring adapters
 * (audit 2026-10-21 follow-up): the model proposes tool calls
 * ({@code finishReason == TOOL_CALL}), the adapter wraps them into a Spring
 * {@link ChatResponse} and Spring's {@link ToolCallingManager} executes the
 * caller-supplied {@code ToolCallback}s; the conversation history it produces
 * (assistant echo + tool results) becomes the next round.
 *
 * <p>Spring runs the tool loop ABOVE the model, unchanged: CortexJ never
 * executes tools. Rounds are bounded by the limits Spring's default tool
 * manager enforces ({@code DEFAULT_MAX_CALLS_PER_TOOL} / total), so a runaway
 * tool surfaces as a typed failure instead of hanging the adapter.</p>
 */
final class CortexJToolLoop {

    private CortexJToolLoop() {
    }

    static boolean hasToolCallbacks(Prompt prompt) {
        return prompt.getOptions() instanceof ToolCallingChatOptions toolOptions
                && toolOptions.getToolCallbacks() != null
                && !toolOptions.getToolCallbacks().isEmpty();
    }

    static ChatResponse run(TextGenerationCapability generator, String modelId, Prompt initialPrompt) {
        ToolCallingManager manager = ToolCallingManager.builder().build();
        Prompt current = initialPrompt;
        while (true) {
            GenerationResponse response = generator.generate(CortexJMapper.generationRequest(current));
            if (response.finishReason() != FinishReason.TOOL_CALL || response.toolCalls().isEmpty()) {
                return CortexJMapper.chatResponse(response, modelId);
            }
            ChatResponse proposal = CortexJMapper.chatResponse(response, modelId);
            org.springframework.ai.model.tool.ToolExecutionResult result =
                    manager.executeToolCalls(current, proposal);
            current = new Prompt(result.conversationHistory(), current.getOptions());
        }
    }
}
