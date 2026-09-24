package io.cortexj.spi.runtime;

import io.cortexj.model.ModelDescriptor;
import io.cortexj.model.ModelArtifact;
import io.cortexj.runtime.DeviceType;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RuntimeModelRequest {

    private final ModelDescriptor model;
    private final ModelArtifact artifact;
    private final Path artifactPath;
    private final DeviceType device;
    private final Map<String, Object> runtimeOptions;

    public RuntimeModelRequest(ModelDescriptor model,
                              ModelArtifact artifact,
                              Path artifactPath,
                              DeviceType device,
                              Map<String, Object> runtimeOptions) {
        this.model = model;
        this.artifact = artifact;
        this.artifactPath = artifactPath;
        this.device = device;
        this.runtimeOptions = runtimeOptions == null
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(runtimeOptions));
    }

    public ModelDescriptor model() {
        return model;
    }

    public ModelArtifact artifact() {
        return artifact;
    }

    public Path artifactPath() {
        return artifactPath;
    }

    public DeviceType device() {
        return device;
    }

    public Map<String, Object> runtimeOptions() {
        return runtimeOptions;
    }
}
