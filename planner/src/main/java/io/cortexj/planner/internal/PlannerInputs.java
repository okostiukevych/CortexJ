package io.cortexj.planner.internal;

import io.cortexj.hardware.EnvironmentProfile;
import io.cortexj.hardware.HardwareProfile;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.spi.artifact.ArtifactInspector;
import io.cortexj.spi.artifact.ArtifactInspection;
import io.cortexj.spi.policy.ExecutionPolicy;
import io.cortexj.spi.policy.PlanningContext;
import io.cortexj.spi.repository.ModelRepositoryProvider;
import io.cortexj.spi.repository.ResolvedArtifact;
import io.cortexj.spi.repository.ResolvedModel;
import io.cortexj.spi.runtime.RuntimeProvider;
import io.cortexj.spi.conversion.ModelConverter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PlannerInputs {

    public final List<RuntimeProvider> runtimeProviders;
    public final List<ModelRepositoryProvider> repositoryProviders;
    public final List<ModelConverter> converters;
    public final List<ExecutionPolicy> policies;
    public final ArtifactInspector inspector;
    public final EnvironmentProfile environment;
    public final HardwareProfile hardware;
    public final PlanningContext.CacheLookup cacheLookup;
    public final boolean offline;
    public final boolean remoteAllowed;
    public final String platformVersion;
    public final long defaultMemoryLimitBytes;

    public PlannerInputs(List<RuntimeProvider> runtimeProviders,
                        List<ModelRepositoryProvider> repositoryProviders,
                        List<ModelConverter> converters,
                        List<ExecutionPolicy> policies,
                        ArtifactInspector inspector,
                        EnvironmentProfile environment,
                        HardwareProfile hardware,
                        PlanningContext.CacheLookup cacheLookup,
                        boolean offline,
                        boolean remoteAllowed,
                        String platformVersion,
                        long defaultMemoryLimitBytes) {
        this.runtimeProviders = immutable(runtimeProviders);
        this.repositoryProviders = immutable(repositoryProviders);
        this.converters = immutable(converters);
        this.policies = immutable(policies);
        this.inspector = inspector;
        this.environment = environment;
        this.hardware = hardware;
        this.cacheLookup = cacheLookup;
        this.offline = offline;
        this.remoteAllowed = remoteAllowed;
        this.platformVersion = platformVersion;
        this.defaultMemoryLimitBytes = defaultMemoryLimitBytes;
    }

    public PlanningContext toPlanningContext(io.cortexj.planner.ExecutionRequest request) {
        return new PlanningContext(environment, hardware, request, cacheLookup);
    }

    public boolean isCached(String repositoryId, String revision, io.cortexj.model.ModelArtifact artifact) {
        if (cacheLookup == null || artifact == null) {
            return false;
        }
        return cacheLookup.isCached(repositoryId, revision, artifact.id(),
                artifact.checksum() == null ? null : artifact.checksum().value());
    }

    public ArtifactInspection inspectIfLocal(ResolvedArtifact artifact) {
        if (inspector == null || artifact == null || artifact.location() == null) {
            return null;
        }
        if (!artifact.location().getScheme().equals("file")) {
            return null;
        }
        if (!artifact.artifact().format().isExecutableWeights()) {
            return null;
        }
        try {
            java.nio.file.Path path = java.nio.file.Paths.get(artifact.location());
            if (!java.nio.file.Files.isRegularFile(path)) {
                return null;
            }
            return inspector.inspect(path);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public Map<String, String> runtimeConfiguration() {
        Map<String, String> config = new LinkedHashMap<String, String>();
        config.put("offline", String.valueOf(offline));
        config.put("remoteAllowed", String.valueOf(remoteAllowed));
        return config;
    }

    public ArtifactFormat dominantExecutableFormat(Iterable<ResolvedArtifact> artifacts) {
        for (ResolvedArtifact artifact : artifacts) {
            if (artifact.artifact().format().isExecutableWeights()) {
                return artifact.artifact().format();
            }
        }
        return ArtifactFormat.UNKNOWN;
    }

    private static <T> List<T> immutable(List<T> list) {
        if (list == null) {
            return new ArrayList<T>();
        }
        return new ArrayList<T>(list);
    }
}
