package io.cortexj.spi.support;

import io.cortexj.model.ModelArtifact;
import io.cortexj.spi.repository.ResolvedArtifact;

import java.net.URI;
import java.util.Map;

public final class SimpleResolvedArtifact extends ResolvedArtifact.Abstract {

    public SimpleResolvedArtifact(ModelArtifact artifact, URI location, Map<String, String> headers) {
        super(artifact, location, headers);
    }
}
