package io.cortexj.inference;

public enum FinishReason {
    STOP,
    LENGTH,
    CONTENT_FILTER,
    TOOL_CALL,
    OTHER
}
