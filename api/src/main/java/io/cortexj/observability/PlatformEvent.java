package io.cortexj.observability;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PlatformEvent {

    public enum Type {
        PLATFORM_STARTED,
        PLATFORM_CLOSED,
        MODEL_RESOLUTION_STARTED,
        MODEL_RESOLUTION_COMPLETED,
        PLAN_CREATED,
        DOWNLOAD_STARTED,
        DOWNLOAD_PROGRESS,
        DOWNLOAD_COMPLETED,
        DOWNLOAD_FAILED,
        RUNTIME_OPENED,
        MODEL_LOADED,
        FALLBACK_APPLIED,
        INFERENCE_FIRST_TOKEN,
        INFERENCE_COMPLETED
    }

    public enum Level { INFO, WARNING, ERROR }

    private final Type type;
    private final Level level;
    private final String message;
    private final Map<String, String> attributes;
    private final long epochMillis;

    public PlatformEvent(Type type, Level level, String message, Map<String, String> attributes, long epochMillis) {
        this.type = type;
        this.level = level;
        this.message = message;
        this.attributes = attributes == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(attributes));
        this.epochMillis = epochMillis;
    }

    public Type type() {
        return type;
    }

    public Level level() {
        return level;
    }

    public String message() {
        return message;
    }

    public Map<String, String> attributes() {
        return attributes;
    }

    public long epochMillis() {
        return epochMillis;
    }
}
