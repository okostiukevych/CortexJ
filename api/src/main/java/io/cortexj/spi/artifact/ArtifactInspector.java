package io.cortexj.spi.artifact;

import io.cortexj.model.ArtifactFormat;

public interface ArtifactInspector {

    boolean supports(ArtifactFormat format);

    ArtifactInspection inspect(java.nio.file.Path artifact);
}
