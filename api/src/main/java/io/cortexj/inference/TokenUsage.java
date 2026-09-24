package io.cortexj.inference;

public final class TokenUsage {

    private final long inputTokens;
    private final long outputTokens;

    public TokenUsage(long inputTokens, long outputTokens) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
    }

    public static TokenUsage unknown() {
        return new TokenUsage(-1L, -1L);
    }

    public long inputTokens() {
        return inputTokens;
    }

    public long outputTokens() {
        return outputTokens;
    }

    public long totalTokens() {
        return inputTokens < 0 || outputTokens < 0 ? -1L : inputTokens + outputTokens;
    }

    public boolean isKnown() {
        return inputTokens >= 0;
    }
}
