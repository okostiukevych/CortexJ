package io.cortexj.stream;

import io.cortexj.error.CortexJException;
import io.cortexj.model.ToolCall;

import java.util.List;

public interface TokenConsumer {

    void onToken(Token token);

    void onComplete(StreamCompletion completion);

    void onError(CortexJException error);

    /**
     * Structured tool calls the model proposed (each batch published after
     * the final token, before the terminal, when finishReason is TOOL_CALL).
     * Default no-op: text-only consumers never see this callback - only
     * agents that execute tools have to implement it.
     */
    default void onToolCalls(List<ToolCall> toolCalls) {
    }
}
