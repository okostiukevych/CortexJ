package io.cortexj.planner;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MemoryEstimate {

    private final long totalBytes;
    private final MemoryConfidence confidence;
    private final Map<String, Long> components;
    private final long safetyMarginBytes;

    private MemoryEstimate(long totalBytes, MemoryConfidence confidence, Map<String, Long> components, long safetyMarginBytes) {
        this.totalBytes = totalBytes;
        this.confidence = confidence == null ? MemoryConfidence.UNKNOWN : confidence;
        this.components = Collections.unmodifiableMap(new LinkedHashMap<String, Long>(components));
        this.safetyMarginBytes = safetyMarginBytes;
    }

    public static MemoryEstimate of(long totalBytes, MemoryConfidence confidence, Map<String, Long> components, long safetyMarginBytes) {
        return new MemoryEstimate(totalBytes, confidence, components, safetyMarginBytes);
    }

    public static MemoryEstimate unknown() {
        return new MemoryEstimate(-1L, MemoryConfidence.UNKNOWN, Collections.<String, Long>emptyMap(), 0L);
    }

    public long totalBytes() {
        return totalBytes;
    }

    public boolean isKnown() {
        return totalBytes >= 0;
    }

    public MemoryConfidence confidence() {
        return confidence;
    }

    public Map<String, Long> components() {
        return components;
    }

    public long safetyMarginBytes() {
        return safetyMarginBytes;
    }

    @Override
    public String toString() {
        if (!isKnown()) {
            return "unknown (" + confidence + ")";
        }
        long mebiBytes = totalBytes / (1024L * 1024L);
        return mebiBytes + " MiB (" + confidence + ")";
    }
}
