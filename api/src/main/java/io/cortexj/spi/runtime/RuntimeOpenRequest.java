package io.cortexj.spi.runtime;

import io.cortexj.hardware.EnvironmentProfile;
import io.cortexj.hardware.HardwareProfile;
import io.cortexj.model.ModelArtifact;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.observability.PlatformEventListener;
import io.cortexj.runtime.DeviceType;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

public final class RuntimeOpenRequest {

    private final ModelDescriptor model;
    private final ModelArtifact artifact;
    private final Path artifactPath;
    private final DeviceType device;
    private final Map<String, Object> runtimeOptions;
    private final Map<String, String> platformConfiguration;
    private final EnvironmentProfile environment;
    private final HardwareProfile hardware;
    private final ExecutorService executor;
    private final PlatformEventListener eventListener;

    public RuntimeOpenRequest(ModelDescriptor model,
                             ModelArtifact artifact,
                             Path artifactPath,
                             DeviceType device,
                             Map<String, Object> runtimeOptions,
                             Map<String, String> platformConfiguration,
                             EnvironmentProfile environment,
                             HardwareProfile hardware,
                             ExecutorService executor,
                             PlatformEventListener eventListener) {
        this.model = model;
        this.artifact = artifact;
        this.artifactPath = artifactPath;
        this.device = device;
        this.runtimeOptions = runtimeOptions == null
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(runtimeOptions));
        this.platformConfiguration = platformConfiguration == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(platformConfiguration));
        this.environment = environment;
        this.hardware = hardware;
        this.executor = executor;
        this.eventListener = eventListener == null ? PlatformEventListener.NOOP : eventListener;
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

    public Map<String, String> platformConfiguration() {
        return platformConfiguration;
    }

    public EnvironmentProfile environment() {
        return environment;
    }

    public HardwareProfile hardware() {
        return hardware;
    }

    public ExecutorService executor() {
        return executor;
    }

    public PlatformEventListener eventListener() {
        return eventListener;
    }
}
