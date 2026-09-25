package io.cortexj.planner;

import io.cortexj.model.ArtifactFormat;

public final class ConversionStep {

    private final String converterId;
    private final String sourceArtifactId;
    private final ArtifactFormat sourceFormat;
    private final ArtifactFormat targetFormat;
    private final String targetQuantization;

    public ConversionStep(String converterId,
                          String sourceArtifactId,
                          ArtifactFormat sourceFormat,
                          ArtifactFormat targetFormat,
                          String targetQuantization) {
        if (converterId == null || converterId.trim().isEmpty()) {
            throw new IllegalArgumentException("converter id must not be empty");
        }
        if (sourceArtifactId == null || sourceArtifactId.trim().isEmpty()) {
            throw new IllegalArgumentException("source artifact id must not be empty");
        }
        this.converterId = converterId;
        this.sourceArtifactId = sourceArtifactId;
        this.sourceFormat = sourceFormat;
        this.targetFormat = targetFormat == null ? ArtifactFormat.UNKNOWN : targetFormat;
        this.targetQuantization = targetQuantization;
    }

    public String converterId() {
        return converterId;
    }

    public String sourceArtifactId() {
        return sourceArtifactId;
    }

    public ArtifactFormat sourceFormat() {
        return sourceFormat;
    }

    public ArtifactFormat targetFormat() {
        return targetFormat;
    }

    public String targetQuantization() {
        return targetQuantization;
    }

    public String describe() {
        StringBuilder text = new StringBuilder();
        text.append("convert ").append(sourceArtifactId)
                .append(" (").append(sourceFormat).append(")")
                .append(" -> ").append(targetFormat);
        if (targetQuantization != null) {
            text.append(" (").append(targetQuantization).append(")");
        }
        text.append(" via ").append(converterId);
        return text.toString();
    }

    @Override
    public String toString() {
        return describe();
    }
}
