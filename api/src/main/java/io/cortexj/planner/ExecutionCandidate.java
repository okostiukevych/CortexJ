package io.cortexj.planner;

import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.runtime.DeviceType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ExecutionCandidate {

    private final String runtimeId;
    private final ModelArtifact artifact;
    private final DeviceType device;
    private final String quantization;
    private final MemoryEstimate memoryEstimate;
    private final boolean requiresDownload;
    private final double score;
    private final Map<String, String> notes;
    private final ConversionStep conversion;
    private final String license;

    private ExecutionCandidate(Builder builder) {
        this.runtimeId = builder.runtimeId;
        this.artifact = builder.artifact;
        this.device = builder.device == null ? DeviceType.CPU : builder.device;
        this.quantization = builder.quantization;
        this.memoryEstimate = builder.memoryEstimate == null ? MemoryEstimate.unknown() : builder.memoryEstimate;
        this.requiresDownload = builder.requiresDownload;
        this.score = builder.score;
        this.notes = Collections.unmodifiableMap(new LinkedHashMap<String, String>(builder.notes));
        this.conversion = builder.conversion;
        this.license = builder.license;
    }

    public String runtimeId() {
        return runtimeId;
    }

    public ModelArtifact artifact() {
        return artifact;
    }

    public ArtifactFormat format() {
        return artifact == null ? ArtifactFormat.UNKNOWN : artifact.format();
    }

    public DeviceType device() {
        return device;
    }

    public String quantization() {
        return quantization;
    }

    public MemoryEstimate memoryEstimate() {
        return memoryEstimate;
    }

    public boolean requiresDownload() {
        return requiresDownload;
    }

    public double score() {
        return score;
    }

    public Map<String, String> notes() {
        return notes;
    }

    public ConversionStep conversion() {
        return conversion;
    }

    public String license() {
        return license;
    }

    public boolean requiresConversion() {
        return conversion != null;
    }

    public java.util.List<PlanReason> notesAsReasons() {
        java.util.List<PlanReason> reasons = new java.util.ArrayList<PlanReason>();
        for (Map.Entry<String, String> entry : notes.entrySet()) {
            ReasonCode code = codeForKey(entry.getKey());
            String message = prettifiedKey(entry.getKey())
                    + ("true".equals(entry.getValue()) ? "" : ": " + entry.getValue());
            reasons.add(code == ReasonCode.LOWER_STARTUP_COST && "tight".equals(entry.getValue())
                    ? PlanReason.negative(code, message)
                    : PlanReason.positive(code, message));
        }
        return reasons;
    }

    private static ReasonCode codeForKey(String key) {
        if (key.equals("cached")) {
            return ReasonCode.LOCAL_CACHE_HIT;
        }
        if (key.equals("nativeFormat")) {
            return ReasonCode.NATIVE_ARTIFACT_AVAILABLE;
        }
        if (key.equals("headroom")) {
            return ReasonCode.MEMORY_HEADROOM_OK;
        }
        if (key.equals("preferredRuntime")) {
            return ReasonCode.USER_PREFERENCE_MATCH;
        }
        if (key.equals("preferredDevice")) {
            return ReasonCode.DEVICE_PREFERENCE_MATCH;
        }
        if (key.equals("preferredQuantization")) {
            return ReasonCode.QUALITY_PROFILE_MATCH;
        }
        if (key.startsWith("policy:")) {
            return ReasonCode.POLICY_APPLIED;
        }
        return ReasonCode.PLANNER_NOTE;
    }

    private static String prettifiedKey(String key) {
        return key.startsWith("policy:") ? key.substring("policy:".length()) : key;
    }

    public String stableKey() {
        return runtimeId + "|" + (artifact == null ? "-" : artifact.id()) + "|" + device + "|" + (quantization == null ? "-" : quantization);
    }

    public static Builder builder(String runtimeId) {
        return new Builder(runtimeId);
    }

    public static final class Builder {

        private final String runtimeId;
        private ModelArtifact artifact;
        private DeviceType device;
        private String quantization;
        private MemoryEstimate memoryEstimate;
        private boolean requiresDownload;
        private double score;
        private ConversionStep conversion;
        private String license;
        private final Map<String, String> notes = new LinkedHashMap<String, String>();

        private Builder(String runtimeId) {
            if (runtimeId == null || runtimeId.trim().isEmpty()) {
                throw new IllegalArgumentException("Candidate runtime id must not be empty");
            }
            this.runtimeId = runtimeId;
        }

        public Builder artifact(ModelArtifact artifact) {
            this.artifact = artifact;
            return this;
        }

        public Builder device(DeviceType device) {
            this.device = device;
            return this;
        }

        public Builder quantization(String quantization) {
            this.quantization = quantization;
            return this;
        }

        public Builder memoryEstimate(MemoryEstimate estimate) {
            this.memoryEstimate = estimate;
            return this;
        }

        public Builder requiresDownload(boolean requiresDownload) {
            this.requiresDownload = requiresDownload;
            return this;
        }

        public Builder score(double score) {
            this.score = score;
            return this;
        }

        public Builder addScore(double delta) {
            this.score += delta;
            return this;
        }

        public Builder conversion(ConversionStep step) {
            this.conversion = step;
            return this;
        }

        public Builder license(String license) {
            this.license = license;
            return this;
        }

        public Builder note(String key, String value) {
            if (value != null) {
                notes.put(key, value);
            }
            return this;
        }

        public ExecutionCandidate build() {
            return new ExecutionCandidate(this);
        }
    }
}
