package io.cortexj.model;

public final class ChatMessage {

    private final ChatRole role;
    private final String content;

    private ChatMessage(ChatRole role, String content) {
        if (role == null) {
            throw new IllegalArgumentException("Chat role must not be null");
        }
        this.role = role;
        this.content = content == null ? "" : content;
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

    public ChatRole role() {
        return role;
    }

    public String content() {
        return content;
    }

    @Override
    public String toString() {
        return role + ": " + content;
    }
}
