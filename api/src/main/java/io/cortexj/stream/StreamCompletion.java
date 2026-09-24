package io.cortexj.stream;

import io.cortexj.inference.FinishReason;
import io.cortexj.inference.TokenUsage;

public final class StreamCompletion {

    private final FinishReason finishReason;
    private final TokenUsage usage;

    public StreamCompletion(FinishReason finishReason, TokenUsage usage) {
        this.finishReason = finishReason == null ? FinishReason.OTHER : finishReason;
        this.usage = usage == null ? TokenUsage.unknown() : usage;
    }

    public FinishReason finishReason() {
        return finishReason;
    }

    public TokenUsage usage() {
        return usage;
    }
}
