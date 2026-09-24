package io.cortexj.spi.conversion;

import io.cortexj.model.ModelArtifact;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ConversionResult {

    private final ModelArtifact outputArtifact;
    private final Path outputPath;
    private final Map<String, String> provenance;

    public ConversionResult(ModelArtifact outputArtifact, Path outputPath, Map<String, String> provenance) {
        this.outputArtifact = outputArtifact;
        this.outputPath = outputPath;
        this.provenance = Collections.unmodifiableMap(new LinkedHashMap<String, String>(provenance));
    }

    public ModelArtifact outputArtifact() {
        return outputArtifact;
    }

    public Path outputPath() {
        return outputPath;
    }

    public Map<String, String> provenance() {
        return provenance;
    }
}
