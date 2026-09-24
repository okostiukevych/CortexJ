package io.cortexj.spi.repository;

import io.cortexj.model.ModelDescriptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ResolvedModel {

    private final ModelDescriptor descriptor;
    private final String revision;
    private final RepositoryId repository;
    private final List<ResolvedArtifact> artifacts;

    public ResolvedModel(ModelDescriptor descriptor, String revision, RepositoryId repository, List<ResolvedArtifact> artifacts) {
        if (descriptor == null) {
            throw new IllegalArgumentException("Resolved model descriptor must not be null");
        }
        this.descriptor = descriptor;
        this.revision = revision;
        this.repository = repository;
        this.artifacts = Collections.unmodifiableList(new ArrayList<ResolvedArtifact>(artifacts));
    }

    public ModelDescriptor descriptor() {
        return descriptor;
    }

    public String revision() {
        return revision;
    }

    public RepositoryId repository() {
        return repository;
    }

    public List<ResolvedArtifact> artifacts() {
        return artifacts;
    }

    public boolean hasRevision() {
        return revision != null && !revision.isEmpty();
    }
}
