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

    private ExecutionCandidate(Builder builder) {
        this.runtimeId = builder.runtimeId;
        this.artifact = builder.artifact;
        this.device = builder.device == null ? DeviceType.CPU : builder.device;
        this.quantization = builder.quantization;
        this.memoryEstimate = builder.memoryEstimate == null ? MemoryEstimate.unknown() : builder.memoryEstimate;
        this.requiresDownload = builder.requiresDownload;
        this.score = builder.score;
        this.notes = Collections.unmodifiableMap(new LinkedHashMap<String, String>(builder.notes));
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
