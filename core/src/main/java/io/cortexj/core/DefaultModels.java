package io.cortexj.core;

import io.cortexj.ErrorCode;
import io.cortexj.error.ArtifactResolutionException;
import io.cortexj.error.ModelLoadException;
import io.cortexj.error.RuntimeUnavailableException;
import io.cortexj.model.ModelArtifact;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelLifecycleState;
import io.cortexj.model.ModelReference;
import io.cortexj.observability.PlatformEvent;
import io.cortexj.planner.ExecutionCandidate;
import io.cortexj.planner.ExecutionPlan;
import io.cortexj.planner.ExecutionRequest;
import io.cortexj.planner.PlanReason;
import io.cortexj.planner.ReasonCode;
import io.cortexj.runtime.DeviceType;
import io.cortexj.spi.repository.ResolvedArtifact;
import io.cortexj.spi.repository.ResolvedModel;
import io.cortexj.spi.runtime.ModelRuntime;
import io.cortexj.spi.runtime.RuntimeModel;
import io.cortexj.spi.runtime.RuntimeOpenRequest;
import io.cortexj.spi.runtime.RuntimeProvider;
import io.cortexj.spi.runtime.RuntimeModelRequest;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class DefaultModels implements io.cortexj.Models {

    private final DefaultCortexJ platform;
    private final List<DefaultModelHandle> openHandles = new CopyOnWriteArrayList<DefaultModelHandle>();

    DefaultModels(DefaultCortexJ platform) {
        this.platform = platform;
    }

    @Override
    public ModelHandle load(ModelReference reference) {
        return load(ExecutionRequest.forModel(reference));
    }

    @Override
    public ModelHandle load(ExecutionRequest request) {
        ExecutionPlan plan = platform.planner().plan(request);
        List<ExecutionCandidate> chain = new ArrayList<ExecutionCandidate>();
        chain.add(plan.selected());
        chain.addAll(plan.fallbacks());

        ModelLoadException last = null;
        for (int index = 0; index < chain.size(); index++) {
            ExecutionCandidate candidate = chain.get(index);
            try {
                DefaultModelHandle handle = loadCandidate(plan, candidate, request);
                if (index > 0) {
                    handle.markFallback(plan.selected().runtimeId(), candidate.runtimeId());
                }
                return handle;
            } catch (RuntimeException e) {
                if (e instanceof ModelLoadException || e instanceof RuntimeUnavailableException
                        || e instanceof ArtifactResolutionException) {
                    last = asLoadFailure(e, candidate);
                    if (index < chain.size() - 1) {
                        platform.events().publish(PlatformEvent.Type.FALLBACK_APPLIED,
                                PlatformEvent.Level.WARNING,
                                "load failed on " + candidate.runtimeId() + ": " + e.getMessage()
                                        + "; trying fallback", null);
                    }
                    continue;
                }
                throw e;
            }
        }
        throw last != null ? last : new ModelLoadException(ErrorCode.LOAD_FAILED,
                "No runtime could load model " + request.model().value(),
                "Inspect the diagnostics with support().inspect() and verify runtimes.");
    }

    private static ModelLoadException asLoadFailure(RuntimeException e, ExecutionCandidate candidate) {
        if (e instanceof ModelLoadException) {
            return (ModelLoadException) e;
        }
        ModelLoadException wrapped = new ModelLoadException(ErrorCode.LOAD_FAILED,
                "Candidate " + candidate.runtimeId() + " could not load the model: " + e.getMessage(), e);
        wrapped.detail("runtime", candidate.runtimeId());
        return wrapped;
    }

    private DefaultModelHandle loadCandidate(ExecutionPlan plan, ExecutionCandidate candidate, ExecutionRequest request) {
        platform.events().info(PlatformEvent.Type.MODEL_RESOLUTION_STARTED,
                "loading " + plan.modelId() + " via " + candidate.runtimeId());

        RuntimeProvider provider = providerById(candidate.runtimeId());
        ResolvedModel resolved = plan.resolvedModel();
        if (resolved == null) {
            throw new ModelLoadException(ErrorCode.LOAD_FAILED,
                    "Execution plan has no resolved model attached",
                    "This is an internal contract violation; report it to the platform maintainers.");
        }

        Path artifactPath = ensureArtifact(plan, resolved, candidate);

        ModelRuntime runtime = provider.open(new RuntimeOpenRequest(
                resolved.descriptor(),
                candidate.artifact(),
                artifactPath,
                candidate.device(),
                planOptions(request, candidate),
                platform.environment(),
                platform.hardware(),
                platform.executor(),
                platform.events().asListener()));

        RuntimeModel model;
        try {
            model = runtime.load(new RuntimeModelRequest(
                    resolved.descriptor(),
                    candidate.artifact(),
                    artifactPath,
                    candidate.device(),
                    planOptions(request, candidate)));
        } catch (RuntimeException e) {
            try {
                runtime.close();
            } catch (RuntimeException closeError) {
            }
            throw e instanceof ModelLoadException ? e : new ModelLoadException(ErrorCode.LOAD_FAILED,
                    "runtime " + candidate.runtimeId() + " failed to load the model: " + e.getMessage(), e);
        }

        platform.events().info(PlatformEvent.Type.MODEL_LOADED,
                "model loaded via " + candidate.runtimeId() + " on " + candidate.device());

        DefaultModelHandle handle = new DefaultModelHandle(
                resolved.descriptor(), plan, model, runtime, platform, candidate);
        openHandles.add(handle);
        return handle;
    }

    private java.util.Map<String, Object> planOptions(ExecutionRequest request, ExecutionCandidate candidate) {
        return request == null
                ? java.util.Collections.<String, Object>emptyMap()
                : request.optionsFor(candidate.runtimeId());
    }

    private RuntimeProvider providerById(String runtimeId) {
        for (RuntimeProvider provider : platform.runtimeProviders()) {
            if (provider.id().value().equals(runtimeId)) {
                return provider;
            }
        }
        throw new RuntimeUnavailableException(ErrorCode.RUNTIME_UNAVAILABLE,
                "Runtime '" + runtimeId + "' is not registered.",
                "Add the corresponding cortexj-runtime-* artifact or register the provider explicitly.")
                .detail("runtime", runtimeId);
    }

    private Path ensureArtifact(ExecutionPlan plan, ResolvedModel resolved, ExecutionCandidate candidate) {
        ModelArtifact artifact = candidate.artifact();
        if (artifact == null || artifact.format() == io.cortexj.model.ArtifactFormat.REMOTE
                || candidate.device() == DeviceType.REMOTE) {
            return null;
        }
        for (ResolvedArtifact resolvedArtifact : resolved.artifacts()) {
            if (!resolvedArtifact.artifact().id().equals(artifact.id())) {
                continue;
            }
            String scheme = resolvedArtifact.location() == null ? "" : resolvedArtifact.location().getScheme();
            if (scheme.equals("file")) {
                return Paths.get(resolvedArtifact.location().getPath());
            }
            io.cortexj.cache.CacheStorage cache = platform.cache();
            io.cortexj.model.Checksum checksum = artifact.checksum();
            Path cached = cache.get(resolved.repository().value(), resolved.revision(), artifact.id(), checksum);
            if (cached != null) {
                return cached;
            }
            if (platform.config().offline) {
                throw new ArtifactResolutionException(ErrorCode.MODEL_OFFLINE_UNRESOLVED,
                        "Artifact " + artifact.id() + " is not cached and offline mode is active.",
                        "Pre-download the artifact with 'cortexj pull' or disable offline mode.")
                        .detail("artifact", artifact.id());
            }
            return platform.downloads().download(resolvedArtifact,
                    resolved.repository().value(), resolved.revision(), cache, 0L);
        }
        throw new ArtifactResolutionException(ErrorCode.ARTIFACT_MISSING,
                "Resolved model does not contain artifact " + artifact.id(),
                "The selected artifact vanished during resolution; re-run the plan.")
                .detail("artifact", artifact.id());
    }

    void closeAll() {
        for (DefaultModelHandle handle : openHandles) {
            try {
                handle.close();
            } catch (RuntimeException ignored) {
            }
        }
        openHandles.clear();
    }

    void unregister(DefaultModelHandle handle) {
        openHandles.remove(handle);
    }

    void handleClosed(DefaultModelHandle handle) {
        unregister(handle);
    }
}
