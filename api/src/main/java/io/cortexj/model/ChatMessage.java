package io.cortexj.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ChatMessage {

    private final ChatRole role;
    private final String content;
    // tool-calling surface (additive): a TOOL message carries the id of the
    // call whose result it delivers, an ASSISTANT message may echo the calls
    // the previous round proposed - the agentic loop controller feeds both
    // back into the next request
    private final String toolCallId;
    private final List<ToolCall> toolCalls;

    private ChatMessage(ChatRole role, String content, String toolCallId, List<ToolCall> toolCalls) {
        if (role == null) {
            throw new IllegalArgumentException("Chat role must not be null");
        }
        this.role = role;
        this.content = content == null ? "" : content;
        this.toolCallId = toolCallId == null ? "" : toolCallId;
        this.toolCalls = toolCalls == null
                ? Collections.<ToolCall>emptyList()
                : Collections.unmodifiableList(new ArrayList<ToolCall>(toolCalls));
    }

    private ChatMessage(ChatRole role, String content) {
        this(role, content, "", null);
    }

    public static ChatMessage of(ChatRole role, String content) {
        return new ChatMessage(role, content);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(ChatRole.SYSTEM, content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(ChatRole.USER, content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(ChatRole.ASSISTANT, content);
    }

    /**
     * Result of an executed tool call, tied back to the proposal by
     * {@code toolCallId}. {@code toolCallId} must not be blank - it is the
     * only link between the result and the call it answers.
     */
    public static ChatMessage tool(String toolCallId, String content) {
        if (toolCallId == null || toolCallId.trim().isEmpty()) {
            throw new IllegalArgumentException("Tool result toolCallId must not be blank");
        }
        return new ChatMessage(ChatRole.TOOL, content, toolCallId, null);
    }

    /**
     * ASSISTANT message that echoes the tool calls proposed in the previous
     * round (typically with empty content). The loop controller sends this
     * back along with the TOOL results so the provider can pair them.
     */
    public static ChatMessage assistantToolCalls(List<ToolCall> toolCalls, String content) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            throw new IllegalArgumentException("Assistant tool-call echo must carry at least one call");
        }
        return new ChatMessage(ChatRole.ASSISTANT, content, "", toolCalls);
    }

    public ChatRole role() {
        return role;
    }

    public String content() {
        return content;
    }

    /** TOOL role only: the id of the answered call, "" for other roles. */
    public String toolCallId() {
        return toolCallId;
    }

    /** ASSISTANT role only: echoed calls, empty for other roles. */
    public List<ToolCall> toolCalls() {
        return toolCalls;
    }

    @Override
    public String toString() {
        return role + ": " + content;
    }
}
