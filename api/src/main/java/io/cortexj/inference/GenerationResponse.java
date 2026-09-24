package io.cortexj.inference;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class GenerationResponse {

    private final String text;
    private final FinishReason finishReason;
    private final TokenUsage usage;
    private final Map<String, String> metadata;

    public GenerationResponse(String text, FinishReason finishReason, TokenUsage usage, Map<String, String> metadata) {
        this.text = text == null ? "" : text;
        this.finishReason = finishReason == null ? FinishReason.OTHER : finishReason;
        this.usage = usage == null ? TokenUsage.unknown() : usage;
        this.metadata = metadata == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(metadata));
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
}
