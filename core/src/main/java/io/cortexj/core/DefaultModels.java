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
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<Path>> inFlightWork =
            new java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<Path>>();
    private final java.util.concurrent.locks.ReentrantReadWriteLock loadLock =
            new java.util.concurrent.locks.ReentrantReadWriteLock();
    private volatile boolean closed;

    DefaultModels(DefaultCortexJ platform) {
        this.platform = platform;
    }

    @Override
    public ModelHandle load(ModelReference reference) {
        return load(ExecutionRequest.forModel(reference));
    }

    @Override
    public ModelHandle load(ExecutionRequest request) {
        loadLock.readLock().lock();
        try {
            if (closed) {
                throw new ModelLoadException(ErrorCode.LOAD_FAILED,
                        "Model load rejected: the CortexJ platform is closed.",
                        "Create a new CortexJ instance to load models after shutdown.");
            }
            return loadWithFallbacks(request);
        } finally {
            loadLock.readLock().unlock();
        }
    }

    private ModelHandle loadWithFallbacks(ExecutionRequest request) {
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

        final DefaultModelHandle handle = new DefaultModelHandle(
                resolved.descriptor(), plan, null, null, platform, candidate);
        handle.onClose(new java.util.function.Consumer<DefaultModelHandle>() {
            @Override
            public void accept(DefaultModelHandle closedHandle) {
                openHandles.remove(closedHandle);
            }
        });
        handle.lifecycleTo(ModelLifecycleState.RESOLVING);
        try {
            enforceTrustPolicy(resolved);

            Path artifactPath = ensureArtifact(plan, resolved, candidate, handle);

            ModelRuntime runtime = provider.open(new RuntimeOpenRequest(
                    resolved.descriptor(),
                    candidate.artifact(),
                    artifactPath,
                    candidate.device(),
                    planOptions(request, candidate),
                    platform.config().runtimeConfiguration(),
                    platform.environment(),
                    platform.hardware(),
                    platform.executor(),
                    platform.events().asListener()));

            handle.lifecycleTo(ModelLifecycleState.LOADING);
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
            handle.attach(model, runtime);

            platform.events().info(PlatformEvent.Type.MODEL_LOADED,
                    "model loaded via " + candidate.runtimeId() + " on " + candidate.device());

            openHandles.add(handle);
            return handle;
        } catch (RuntimeException e) {
            handle.lifecycleTo(ModelLifecycleState.FAILED);
            throw e;
        }
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

    private Path ensureArtifact(ExecutionPlan plan, ResolvedModel resolved, ExecutionCandidate candidate,
                                DefaultModelHandle handle) {
        ModelArtifact artifact = candidate.artifact();
        if (artifact == null || artifact.format() == io.cortexj.model.ArtifactFormat.REMOTE
                || candidate.device() == DeviceType.REMOTE) {
            return null;
        }
        if (candidate.requiresConversion()) {
            return materializeConversion(plan, resolved, candidate, handle);
        }
        for (ResolvedArtifact resolvedArtifact : resolved.artifacts()) {
            if (!resolvedArtifact.artifact().id().equals(artifact.id())) {
                continue;
            }
            String scheme = resolvedArtifact.location() == null ? "" : resolvedArtifact.location().getScheme();
            if (scheme.equals("file")) {
                return Paths.get(resolvedArtifact.location());
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
            return downloadOnce(resolvedArtifact, resolved.repository().value(),
                    resolved.revision(), cache, handle);
        }
        throw new ArtifactResolutionException(ErrorCode.ARTIFACT_MISSING,
                "Resolved model does not contain artifact " + artifact.id(),
                "The selected artifact vanished during resolution; re-run the plan.")
                .detail("artifact", artifact.id());
    }

    private Path oncePerKey(String key, java.util.function.Supplier<Path> work) {
        java.util.concurrent.CompletableFuture<Path> claim =
                new java.util.concurrent.CompletableFuture<Path>();
        java.util.concurrent.CompletableFuture<Path> inFlight = inFlightWork.putIfAbsent(key, claim);
        if (inFlight != null) {
            return awaitInFlight(inFlight);
        }
        try {
            Path result = work.get();
            claim.complete(result);
            return result;
        } catch (RuntimeException failure) {
            claim.completeExceptionally(failure);
            throw failure;
        } finally {
            inFlightWork.remove(key, claim);
        }
    }

    private Path downloadOnce(ResolvedArtifact resolvedArtifact, String repositoryId, String revision,
                               io.cortexj.cache.CacheStorage cache, final DefaultModelHandle handle) {
        String key = repositoryId + "~" + revision + "~" + resolvedArtifact.artifact().id();
        return oncePerKey(key, new java.util.function.Supplier<Path>() {
            @Override
            public Path get() {
                Path cached = cache.get(repositoryId, revision, resolvedArtifact.artifact().id(),
                        resolvedArtifact.artifact().checksum());
                if (cached != null) {
                    return cached;
                }
                handle.lifecycleTo(ModelLifecycleState.DOWNLOADING);
                return platform.downloads().download(resolvedArtifact, repositoryId,
                        revision, cache, 0L);
            }
        });
    }

    private Path awaitInFlight(java.util.concurrent.CompletableFuture<Path> inFlight) {
        try {
            return inFlight.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelLoadException(ErrorCode.LOAD_FAILED,
                    "Interrupted while waiting for a concurrent download of the same artifact",
                    "Retry the model load.");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new ModelLoadException(ErrorCode.LOAD_FAILED,
                    "Concurrent download of the same artifact failed: " + cause.getMessage(), cause);
        }
    }

    private void enforceTrustPolicy(ResolvedModel resolved) {
        io.cortexj.model.TrustPolicy policy = platform.config().trustPolicy;
        String trustNote = resolved.descriptor().trustNote();
        if (trustNote == null || trustNote.isEmpty()) {
            return;
        }
        if (policy == io.cortexj.model.TrustPolicy.TRUSTED_ONLY) {
            throw new ModelLoadException(io.cortexj.ErrorCode.SEC_POLICY_BLOCKED,
                    "Model " + resolved.descriptor().canonicalId()
                            + " is not explicitly trusted and the platform runs with TRUSTED_ONLY: "
                            + trustNote,
                    "Verify the model publisher and checksums, pin a trusted revision, "
                            + "or relax the trust policy to ALLOW_UNSIGNED_WITH_WARNING.");
        }
        if (policy == io.cortexj.model.TrustPolicy.ALLOW_UNSIGNED_WITH_WARNING) {
            platform.events().warn(PlatformEvent.Type.MODEL_RESOLUTION_COMPLETED,
                    "untrusted model loaded (trust policy allows it): " + trustNote);
        }
    }

    private Path materializeConversion(ExecutionPlan plan, ResolvedModel resolved, ExecutionCandidate candidate,
                                        DefaultModelHandle handle) {
        io.cortexj.planner.ConversionStep step = candidate.conversion();
        ResolvedArtifact source = null;
        for (ResolvedArtifact resolvedArtifact : resolved.artifacts()) {
            if (resolvedArtifact.artifact().id().equals(step.sourceArtifactId())) {
                source = resolvedArtifact;
                break;
            }
        }
        if (source == null) {
            throw new ArtifactResolutionException(ErrorCode.ARTIFACT_MISSING,
                    "Conversion source artifact " + step.sourceArtifactId()
                            + " is not part of the resolved model",
                    "The repository content changed after planning; re-run the plan.")
                    .detail("artifact", step.sourceArtifactId());
        }
        String repositoryId = resolved.repository().value();
        String revision = resolved.revision();
        String convertedId = candidate.artifact().id();

        io.cortexj.cache.CacheStorage cache = platform.cache();
        Path cached = cache.get(repositoryId, revision, convertedId, null);
        if (cached != null) {
            return cached;
        }

        handle.lifecycleTo(ModelLifecycleState.PREPARING);
        final ResolvedArtifact conversionSource = source;
        String conversionKey = repositoryId + "~" + revision + "~" + convertedId + "~conversion";
        return oncePerKey(conversionKey, new java.util.function.Supplier<Path>() {
            @Override
            public Path get() {
                return runConversion(plan, resolved, candidate, step, conversionSource,
                        repositoryId, revision, convertedId, cache, handle);
            }
        });
    }

    private Path runConversion(ExecutionPlan plan, ResolvedModel resolved, ExecutionCandidate candidate,
                               io.cortexj.planner.ConversionStep step, ResolvedArtifact source,
                               String repositoryId, String revision, String convertedId,
                               io.cortexj.cache.CacheStorage cache, DefaultModelHandle handle) {
        Path sourcePath = ensureSourceArtifact(resolved, source, handle);
        handle.lifecycleTo(ModelLifecycleState.PREPARING);
        Path outputDirectory;
        try {
            outputDirectory = java.nio.file.Files.createTempDirectory("cortexj-conversion");
        } catch (java.io.IOException e) {
            throw new ModelLoadException(ErrorCode.LOAD_FAILED,
                    "Cannot create a conversion working directory: " + e.getMessage(), e);
        }
        try {
            io.cortexj.spi.conversion.ModelConverter converter = converterById(step.converterId());
            if (converter == null) {
                throw new ModelLoadException(ErrorCode.LOAD_FAILED,
                        "Converter " + step.converterId() + " is not registered on this platform",
                        "Add the converter module to the assembly or re-plan without conversion.");
            }
            io.cortexj.spi.conversion.ConversionResult result;
            try {
                result = converter.convert(
                        new io.cortexj.spi.conversion.ConversionRequest(
                                source.artifact(), sourcePath, step.targetFormat(),
                                step.targetQuantization(), outputDirectory,
                                java.util.Collections.<String, Object>emptyMap()));
            } catch (RuntimeException e) {
                throw new ModelLoadException(ErrorCode.LOAD_FAILED,
                        "Converter " + step.converterId() + " failed on "
                                + step.sourceArtifactId() + ": " + e.getMessage(), e);
            }
            Path outputPath = result.outputPath();
            if (outputPath == null || !java.nio.file.Files.isRegularFile(outputPath)) {
                throw new ModelLoadException(ErrorCode.LOAD_FAILED,
                        "Converter " + step.converterId() + " produced no output artifact",
                        "Report the converter failure to the converter maintainer.");
            }
            String sha256;
            try (java.io.InputStream input = java.nio.file.Files.newInputStream(outputPath)) {
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
                StringBuilder hex = new StringBuilder();
                for (byte b : digest.digest()) {
                    hex.append(String.format("%02x", b));
                }
                sha256 = hex.toString();
            }
            try (java.io.InputStream input = java.nio.file.Files.newInputStream(outputPath)) {
                cache.put(repositoryId, revision, convertedId, null, input);
            }
            writeProvenance(resolved, candidate, step, converter, source, result, sha256);
            return cache.get(repositoryId, revision, convertedId, null);
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) {
            throw new ModelLoadException(ErrorCode.LOAD_FAILED,
                    "Conversion of " + step.sourceArtifactId() + " failed: " + e.getMessage(), e);
        } finally {
            deleteRecursively(outputDirectory);
        }
    }

    private static void deleteRecursively(Path directory) {
        if (directory == null) {
            return;
        }
        try {
            java.nio.file.Files.walkFileTree(directory, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file,
                                                                java.nio.file.attribute.BasicFileAttributes attrs)
                        throws java.io.IOException {
                    java.nio.file.Files.deleteIfExists(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path dir, java.io.IOException failure)
                        throws java.io.IOException {
                    java.nio.file.Files.deleteIfExists(dir);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (java.io.IOException ignored) {
            // best-effort cleanup of the temporary conversion workspace
        }
    }

    private Path ensureSourceArtifact(ResolvedModel resolved, ResolvedArtifact source, DefaultModelHandle handle) {
        String scheme = source.location() == null ? "" : source.location().getScheme();
        if (scheme.equals("file")) {
            return Paths.get(source.location());
        }
        io.cortexj.cache.CacheStorage cache = platform.cache();
        Path cached = cache.get(resolved.repository().value(), resolved.revision(),
                source.artifact().id(), source.artifact().checksum());
        if (cached != null) {
            return cached;
        }
        if (platform.config().offline) {
            throw new ArtifactResolutionException(ErrorCode.MODEL_OFFLINE_UNRESOLVED,
                    "Conversion source artifact " + source.artifact().id()
                            + " is not cached and offline mode is active.",
                    "Pre-download the artifact with 'cortexj pull' or disable offline mode.")
                    .detail("artifact", source.artifact().id());
        }
        return downloadOnce(source, resolved.repository().value(), resolved.revision(), cache, handle);
    }

    private io.cortexj.spi.conversion.ModelConverter converterById(String converterId) {
        for (io.cortexj.spi.conversion.ModelConverter converter : platform.converters()) {
            if (converter.id().value().equals(converterId)) {
                return converter;
            }
        }
        return null;
    }

    private void writeProvenance(ResolvedModel resolved,
                                 ExecutionCandidate candidate,
                                 io.cortexj.planner.ConversionStep step,
                                 io.cortexj.spi.conversion.ModelConverter converter,
                                 ResolvedArtifact source,
                                 io.cortexj.spi.conversion.ConversionResult result,
                                 String outputSha256) {
        try {
            java.nio.file.Path directory = platform.config().workingDirectory
                    .resolve("converted").resolve(resolved.repository().value())
                    .resolve(resolved.revision());
            java.nio.file.Files.createDirectories(directory);
            java.util.Properties manifest = new java.util.Properties();
            manifest.setProperty("sourceRevision", resolved.revision());
            manifest.setProperty("sourceArtifact", source.artifact().id());
            String sourceChecksum = source.artifact().checksum() == null
                    ? "" : source.artifact().checksum().value();
            manifest.setProperty("sourceChecksum", sourceChecksum);
            manifest.setProperty("converterId", converter.id().value());
            manifest.setProperty("targetFormat", step.targetFormat().name());
            if (step.targetQuantization() != null) {
                manifest.setProperty("quantization", step.targetQuantization());
            }
            if (candidate.quantization() != null) {
                manifest.setProperty("candidateQuantization", candidate.quantization());
            }
            manifest.setProperty("outputArtifact", candidate.artifact().id());
            manifest.setProperty("outputChecksum", "sha256:" + outputSha256);
            manifest.setProperty("timestamp", java.time.format.DateTimeFormatter.ISO_INSTANT
                    .format(java.time.Instant.now()));
            manifest.setProperty("platformVersion", io.cortexj.core.CortexJInfo.VERSION);
            manifest.setProperty("originalArtifactImmutable", "true");
            for (java.util.Map.Entry<String, String> entry : result.provenance().entrySet()) {
                manifest.setProperty("converter." + entry.getKey(), entry.getValue());
            }
            java.io.OutputStream out = java.nio.file.Files.newOutputStream(
                    directory.resolve(candidate.artifact().id() + ".provenance.properties"));
            try {
                manifest.store(out, "CortexJ deterministic conversion provenance (spec 32.4)");
            } finally {
                out.close();
            }
        } catch (java.io.IOException ignored) {
            // provenance is best-effort and must not block model loading
        }
    }

    void closeAll() {
        loadLock.writeLock().lock();
        try {
            closed = true;
            for (DefaultModelHandle handle : openHandles) {
                try {
                    handle.close();
                } catch (RuntimeException ignored) {
                }
            }
            openHandles.clear();
        } finally {
            loadLock.writeLock().unlock();
        }
    }

    void unregister(DefaultModelHandle handle) {
        openHandles.remove(handle);
    }

    int openHandleCount() {
        return openHandles.size();
    }

    void handleClosed(DefaultModelHandle handle) {
        unregister(handle);
    }
}
