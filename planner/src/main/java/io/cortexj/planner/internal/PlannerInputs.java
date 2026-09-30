package io.cortexj.planner.internal;

import io.cortexj.hardware.EnvironmentProfile;
import io.cortexj.hardware.HardwareProfile;
import io.cortexj.observability.PlatformEventListener;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
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
    public final io.cortexj.model.artifact.ArtifactInspectorRegistry inspector;
    public final EnvironmentProfile environment;
    public final HardwareProfile hardware;
    public final PlanningContext.CacheLookup cacheLookup;
    public final io.cortexj.spi.repository.TokenSupplier tokenSupplier;
    public final java.util.concurrent.ExecutorService executor;
    public final PlatformEventListener eventListener;
    public final boolean offline;
    public final boolean remoteAllowed;
    public final String platformVersion;
    public final long defaultMemoryLimitBytes;

    private final Map<String, String> runtimeConfiguration;

    public PlannerInputs(List<RuntimeProvider> runtimeProviders,
                        List<ModelRepositoryProvider> repositoryProviders,
                        List<ModelConverter> converters,
                        List<ExecutionPolicy> policies,
                        io.cortexj.model.artifact.ArtifactInspectorRegistry inspector,
                        EnvironmentProfile environment,
                        HardwareProfile hardware,
                        PlanningContext.CacheLookup cacheLookup,
                        io.cortexj.spi.repository.TokenSupplier tokenSupplier,
                        java.util.concurrent.ExecutorService executor,
                        PlatformEventListener eventListener,
                        boolean offline,
                        boolean remoteAllowed,
                        String platformVersion,
                        long defaultMemoryLimitBytes) {
        this(runtimeProviders, repositoryProviders, converters, policies, inspector, environment,
                hardware, cacheLookup, tokenSupplier, executor, eventListener, offline, remoteAllowed,
                platformVersion, defaultMemoryLimitBytes,
                java.util.Collections.<String, String>emptyMap());
    }

    public PlannerInputs(List<RuntimeProvider> runtimeProviders,
                        List<ModelRepositoryProvider> repositoryProviders,
                        List<ModelConverter> converters,
                        List<ExecutionPolicy> policies,
                        io.cortexj.model.artifact.ArtifactInspectorRegistry inspector,
                        EnvironmentProfile environment,
                        HardwareProfile hardware,
                        PlanningContext.CacheLookup cacheLookup,
                        io.cortexj.spi.repository.TokenSupplier tokenSupplier,
                        java.util.concurrent.ExecutorService executor,
                        PlatformEventListener eventListener,
                        boolean offline,
                        boolean remoteAllowed,
                        String platformVersion,
                        long defaultMemoryLimitBytes,
                        Map<String, String> runtimeConfiguration) {
        this.runtimeProviders = immutable(runtimeProviders);
        this.repositoryProviders = immutable(repositoryProviders);
        this.converters = immutable(converters);
        this.policies = immutable(policies);
        this.inspector = inspector;
        this.environment = environment;
        this.hardware = hardware;
        this.cacheLookup = cacheLookup;
        this.tokenSupplier = tokenSupplier == null ? io.cortexj.spi.repository.TokenSupplier.none() : tokenSupplier;
        this.executor = executor;
        this.eventListener = eventListener == null ? PlatformEventListener.NOOP : eventListener;
        this.offline = offline;
        this.remoteAllowed = remoteAllowed;
        this.platformVersion = platformVersion;
        this.defaultMemoryLimitBytes = defaultMemoryLimitBytes;
        this.runtimeConfiguration = runtimeConfiguration == null
                ? java.util.Collections.<String, String>emptyMap()
                : java.util.Collections.unmodifiableMap(
                        new LinkedHashMap<String, String>(runtimeConfiguration));
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

    public ArtifactInspection inspectIfLocal(ResolvedModel resolved, ResolvedArtifact artifact) {
        if (inspector == null || artifact == null || artifact.location() == null) {
            return null;
        }
        if (!artifact.artifact().format().isExecutableWeights()) {
            return null;
        }
        try {
            java.nio.file.Path path = null;
            if (artifact.location().getScheme().equals("file")) {
                path = java.nio.file.Paths.get(artifact.location());
            } else if (resolved != null && resolved.hasRevision() && cacheLookup != null) {
                path = cacheLookup.cachedArtifactPath(resolved.repository().value(),
                        resolved.revision(), artifact.artifact().id(),
                        artifact.artifact().checksum() == null
                                ? null : artifact.artifact().checksum().value());
            }
            if (path == null || !java.nio.file.Files.isRegularFile(path)) {
                return null;
            }
            return inspector.inspectOrNull(artifact.artifact().format(), path);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public Map<String, String> runtimeConfiguration() {
        Map<String, String> config = new LinkedHashMap<String, String>(runtimeConfiguration);
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
            return java.util.Collections.emptyList();
        }
        return java.util.Collections.unmodifiableList(new ArrayList<T>(list));
    }
}
