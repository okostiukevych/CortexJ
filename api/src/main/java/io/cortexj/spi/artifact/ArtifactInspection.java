package io.cortexj.spi.artifact;

import io.cortexj.model.ArtifactFormat;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ArtifactInspection {

    private final ArtifactFormat format;
    private final String architecture;
    private final long parameterCount;
    private final int contextLength;
    private final String quantization;
    private final Map<String, String> metadata;

    private ArtifactInspection(Builder builder) {
        this.format = builder.format;
        this.architecture = builder.architecture;
        this.parameterCount = builder.parameterCount;
        this.contextLength = builder.contextLength;
        this.quantization = builder.quantization;
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<String, String>(builder.metadata));
    }

    public static Builder builder(ArtifactFormat format) {
        return new Builder(format);
    }

    public ArtifactFormat format() {
        return format;
    }

    public String architecture() {
        return architecture;
    }

    public long parameterCount() {
        return parameterCount;
    }

    public int contextLength() {
        return contextLength;
    }

    public String quantization() {
        return quantization;
    }

    public Map<String, String> metadata() {
        return metadata;
    }

    public static final class Builder {

        private final ArtifactFormat format;
        private String architecture;
        private long parameterCount;
        private int contextLength;
        private String quantization;
        private final Map<String, String> metadata = new LinkedHashMap<String, String>();

        private Builder(ArtifactFormat format) {
            this.format = format;
        }

        public Builder architecture(String value) {
            this.architecture = value;
            return this;
        }

        public Builder parameterCount(long value) {
            this.parameterCount = value;
            return this;
        }

        public Builder contextLength(int value) {
            this.contextLength = value;
            return this;
        }

        public Builder quantization(String value) {
            this.quantization = value;
            return this;
        }

        public Builder metadata(String key, String value) {
            if (value != null) {
                metadata.put(key, value);
            }
            return this;
        }

        public ArtifactInspection build() {
            return new ArtifactInspection(this);
        }
    }
}
