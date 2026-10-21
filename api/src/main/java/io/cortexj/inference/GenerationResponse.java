package io.cortexj.inference;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.cortexj.model.ToolCall;

public final class GenerationResponse {

    private final String text;
    private final FinishReason finishReason;
    private final TokenUsage usage;
    private final Map<String, String> metadata;
    // additive tool-calling surface: calls the model proposed (finish reason
    // TOOL_CALL); text-only responses carry an empty list
    private final List<ToolCall> toolCalls;

    public GenerationResponse(String text, FinishReason finishReason, TokenUsage usage, Map<String, String> metadata) {
        this(text, finishReason, usage, metadata, null);
    }

    public GenerationResponse(String text, FinishReason finishReason, TokenUsage usage,
                              Map<String, String> metadata, List<ToolCall> toolCalls) {
        this.text = text == null ? "" : text;
        this.finishReason = finishReason == null ? FinishReason.OTHER : finishReason;
        this.usage = usage == null ? TokenUsage.unknown() : usage;
        this.metadata = metadata == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(metadata));
        this.toolCalls = toolCalls == null
                ? Collections.<ToolCall>emptyList()
                : Collections.unmodifiableList(new ArrayList<ToolCall>(toolCalls));
    }

    public String text() {
        return text;
    }

    public FinishReason finishReason() {
        return finishReason;
    }

    public TokenUsage usage() {
        return usage;
    }

    public Map<String, String> metadata() {
        return metadata;
    }

    /** Calls the model proposed (empty unless {@code finishReason} is TOOL_CALL). */
    public List<ToolCall> toolCalls() {
        return toolCalls;
    }
}
