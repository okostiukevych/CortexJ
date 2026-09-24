package io.cortexj.spi.conversion;

import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ConversionRequest {

    private final ModelArtifact sourceArtifact;
    private final Path sourcePath;
    private final ArtifactFormat targetFormat;
    private final String targetQuantization;
    private final Path outputDirectory;
    private final Map<String, Object> parameters;

    public ConversionRequest(ModelArtifact sourceArtifact, Path sourcePath, ArtifactFormat targetFormat, String targetQuantization, Path outputDirectory, Map<String, Object> parameters) {
        this.sourceArtifact = sourceArtifact;
        this.sourcePath = sourcePath;
        this.targetFormat = targetFormat;
        this.targetQuantization = targetQuantization;
        this.outputDirectory = outputDirectory;
        this.parameters = parameters == null
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(parameters));
    }

    public ModelArtifact sourceArtifact() {
        return sourceArtifact;
    }

    public Path sourcePath() {
        return sourcePath;
    }

    public ArtifactFormat targetFormat() {
        return targetFormat;
    }

    public String targetQuantization() {
        return targetQuantization;
    }

    public Path outputDirectory() {
        return outputDirectory;
    }

    public Map<String, Object> parameters() {
        return parameters;
    }
}
