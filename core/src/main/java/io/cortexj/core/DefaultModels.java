package io.cortexj.core;

import io.cortexj.ErrorCode;
import io.cortexj.error.ArtifactIntegrityException;
import io.cortexj.error.ArtifactResolutionException;
import io.cortexj.error.CortexJException;
import io.cortexj.error.ModelLoadException;
import io.cortexj.error.NativeRuntimeException;
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
import java.util.concurrent.TimeUnit;

public final class DefaultModels implements io.cortexj.Models {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(DefaultModels.class.getName());

    private static final long DEFAULT_AWAIT_TIMEOUT_MILLIS = 10L * 60L * 1000L;

    private final DefaultCortexJ platform;
    private final List<DefaultModelHandle> openHandles = new CopyOnWriteArrayList<DefaultModelHandle>();
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<Path>> inFlightWork =
            new java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<Path>>();
    private final java.util.concurrent.locks.ReentrantReadWriteLock loadLock =
            new java.util.concurrent.locks.ReentrantReadWriteLock(true);
    private final java.util.concurrent.ConcurrentHashMap<DefaultModelHandle, ArtifactPin> pinsByHandle =
            new java.util.concurrent.ConcurrentHashMap<DefaultModelHandle, ArtifactPin>();
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> pinCounts =
            new java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>();
    private final java.util.concurrent.ConcurrentHashMap<String, Object> pinLocks =
            new java.util.concurrent.ConcurrentHashMap<String, Object>();
    private final java.util.concurrent.CopyOnWriteArrayList<java.util.concurrent.CompletableFuture<ModelHandle>> asyncLoads =
            new java.util.concurrent.CopyOnWriteArrayList<java.util.concurrent.CompletableFuture<ModelHandle>>();
    private volatile boolean closed;

    DefaultModels(DefaultCortexJ platform) {
        this.platform = platform;
    }

    private static final class ArtifactPin {

        final String repositoryId;
        final String revision;
        final String artifactId;

        ArtifactPin(String repositoryId, String revision, String artifactId) {
            this.repositoryId = repositoryId;
            this.revision = revision;
            this.artifactId = artifactId;
        }

        String identity() {
            return repositoryId + '~' + revision + '~' + artifactId;
        }
    }

    private void acquirePin(DefaultModelHandle handle, ResolvedModel resolved, ExecutionCandidate candidate) {
        io.cortexj.model.ModelArtifact artifact = candidate.artifact();
        if (artifact == null || artifact.format() == io.cortexj.model.ArtifactFormat.REMOTE) {
            return;
        }
        String repositoryId = resolved.repository().value();
        String revision = resolved.revision();
        String artifactId = artifact.id();
        io.cortexj.cache.CacheStorage cache = platform.cache();
        if (!cache.contains(repositoryId, revision, artifactId)) {
            return;
        }
        try {
            cache.pin(repositoryId, revision, artifactId);
        } catch (java.io.IOException pinFailure) {
            LOG.log(java.util.logging.Level.FINE,
                    "artifact pinning skipped for " + artifactId, pinFailure);
            return;
        }
        ArtifactPin pin = new ArtifactPin(repositoryId, revision, artifactId);
        // audit #94: the whole acquire (marker + registry + count) is serialized
        // per identity so release can never remove a counter a live handle re-created
        synchronized (pinLockFor(pin.identity())) {
            pinsByHandle.put(handle, pin);
            java.util.concurrent.atomic.AtomicInteger count = pinCounts.computeIfAbsent(
                    pin.identity(), new java.util.function.Function<String, java.util.concurrent.atomic.AtomicInteger>() {
                        @Override
                        public java.util.concurrent.atomic.AtomicInteger apply(String key) {
                            return new java.util.concurrent.atomic.AtomicInteger();
                        }
                    });
            count.incrementAndGet();
        }
    }

    private Object pinLockFor(String identity) {
        return pinLocks.computeIfAbsent(identity, new java.util.function.Function<String, Object>() {
            @Override
            public Object apply(String key) {
                return new Object();
            }
        });
    }

    private void releasePin(DefaultModelHandle handle) {
        ArtifactPin pin = pinsByHandle.remove(handle);
        if (pin == null) {
            return;
        }
        // audit #94: decrement + removal + unpin under the same per-identity lock
        // as acquire, so a concurrent acquire either joins this pin's count
        // (2 -> 1, no removal) or re-creates a fresh counter after the removal
        synchronized (pinLockFor(pin.identity())) {
            java.util.concurrent.atomic.AtomicInteger count = pinCounts.get(pin.identity());
            if (count == null) {
                return;
            }
            if (count.decrementAndGet() <= 0) {
                pinCounts.remove(pin.identity(), count);
                try {
                    platform.cache().unpin(pin.repositoryId, pin.revision, pin.artifactId);
                } catch (java.io.IOException unpinFailure) {
                    LOG.log(java.util.logging.Level.FINE,
                            "artifact unpinning failed for " + pin.artifactId, unpinFailure);
                }
            }
        }
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
            return loadWithFallbacks(request, new java.util.concurrent.atomic.AtomicBoolean());
        } finally {
            loadLock.readLock().unlock();
        }
    }

    @Override
    public java.util.concurrent.Future<ModelHandle> loadAsync(ModelReference reference) {
        return loadAsync(ExecutionRequest.forModel(reference));
    }

    @Override
    public java.util.concurrent.Future<ModelHandle> loadAsync(final ExecutionRequest request) {
        loadLock.readLock().lock();
        try {
            if (closed) {
                java.util.concurrent.CompletableFuture<ModelHandle> rejected =
                        new java.util.concurrent.CompletableFuture<ModelHandle>();
                rejected.completeExceptionally(new ModelLoadException(ErrorCode.LOAD_FAILED,
                        "Model load rejected: the CortexJ platform is closed.",
                        "Create a new CortexJ instance to load models after shutdown."));
                return rejected;
            }
            final java.util.concurrent.atomic.AtomicBoolean cancelFlag =
                    new java.util.concurrent.atomic.AtomicBoolean();
            final java.util.concurrent.CompletableFuture<ModelHandle> underlying =
                    new java.util.concurrent.CompletableFuture<ModelHandle>();
            try {
                // audit 2026-10-07 (#145): single registration - the list
                // previously received the same future twice, leaving a stale
                // entry (and a double completeExceptionally) until closeAll()
                // audit 2026-10-07 (#146): async loads run on the dedicated
                // load pool - a stuck native load must not consume download
                // slots (or the reverse), starving queued work
                asyncLoads.add(underlying);
                platform.loadExecutor().execute(new Runnable() {
                    @Override
                    public void run() {
                        // audit #89: the task body runs inside the close barrier
                        // (same contract as the synchronous load) - either it
                        // finishes before closeAll() takes the write lock, or it
                        // observes closed and fails the future instead of leaking
                        // a handle nobody will ever close
                        loadLock.readLock().lock();
                        try {
                            if (closed) {
                                underlying.completeExceptionally(new ModelLoadException(
                                        ErrorCode.LOAD_FAILED,
                                        "Model load rejected: the CortexJ platform is closed.",
                                        "Create a new CortexJ instance to load models after shutdown."));
                                return;
                            }
                            if (cancelFlag.get()) {
                                underlying.completeExceptionally(
                                        new java.util.concurrent.CancellationException(
                                                "load cancelled before it started"));
                                return;
                            }
                            ModelHandle handle = loadWithFallbacks(request, cancelFlag);
                            if (cancelFlag.get()) {
                                closeHandleAfterCancellation(handle);
                                underlying.completeExceptionally(
                                        new java.util.concurrent.CancellationException(
                                                "load cancelled during execution"));
                                return;
                            }
                            if (!underlying.complete(handle) && cancelFlag.get()) {
                                closeHandleAfterCancellation(handle);
                            }
                        } catch (Throwable failure) {
                            underlying.completeExceptionally(failure);
                        } finally {
                            asyncLoads.remove(underlying);
                            loadLock.readLock().unlock();
                        }
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException saturated) {
                asyncLoads.remove(underlying);
                underlying.completeExceptionally(new ModelLoadException(ErrorCode.LOAD_FAILED,
                        "Model load rejected: too many concurrent loads are already queued "
                                + "(" + saturated.getMessage() + ").",
                        "Await or cancel an in-flight loadAsync before submitting more, or raise "
                                + "the load pool width (cortexj.load.width/cortexj.load.queue)."));
            }
            return new CancellableLoad(underlying, cancelFlag);
        } finally {
            loadLock.readLock().unlock();
        }
    }

    private static final class CancellableLoad implements java.util.concurrent.Future<ModelHandle> {

        private final java.util.concurrent.CompletableFuture<ModelHandle> underlying;
        private final java.util.concurrent.atomic.AtomicBoolean cancelFlag;

        CancellableLoad(java.util.concurrent.CompletableFuture<ModelHandle> underlying,
                        java.util.concurrent.atomic.AtomicBoolean cancelFlag) {
            this.underlying = underlying;
            this.cancelFlag = cancelFlag;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelFlag.set(true);
            return underlying.cancel(mayInterruptIfRunning);
        }

        @Override
        public boolean isCancelled() {
            return underlying.isCancelled();
        }

        @Override
        public boolean isDone() {
            return underlying.isDone();
        }

        @Override
        public ModelHandle get() throws InterruptedException, java.util.concurrent.ExecutionException {
            return underlying.get();
        }

        @Override
        public ModelHandle get(long timeout, TimeUnit unit)
                throws InterruptedException, java.util.concurrent.ExecutionException,
                java.util.concurrent.TimeoutException {
            return underlying.get(timeout, unit);
        }
    }

    private ModelHandle loadWithFallbacks(ExecutionRequest request,
                                          java.util.concurrent.atomic.AtomicBoolean cancelFlag) {
        ExecutionPlan plan = platform.planner().plan(request);
        List<ExecutionCandidate> chain = new ArrayList<ExecutionCandidate>();
        chain.add(plan.selected());
        chain.addAll(plan.fallbacks());

        ModelLoadException last = null;
        for (int index = 0; index < chain.size(); index++) {
            if (cancelFlag != null && cancelFlag.get()) {
                throw new java.util.concurrent.CancellationException(
                        "load cancelled before candidate " + chain.get(index).runtimeId());
            }
            ExecutionCandidate candidate = chain.get(index);
            try {
                DefaultModelHandle handle = loadCandidate(plan, candidate, request, cancelFlag);
                if (index > 0) {
                    handle.markFallback(plan.selected().runtimeId(), candidate.runtimeId());
                }
                return handle;
            } catch (RuntimeException e) {
                // audit 2026-10-07 (#147): a trust/license refusal is
                // provider-independent - retrying the next runtime is
                // pointless, fail fast with the policy error instead
                if (isPolicyRefusal(e)) {
                    throw e instanceof ModelLoadException ? e : asLoadFailure(e, candidate);
                }
                if (e instanceof ModelLoadException || e instanceof RuntimeUnavailableException
                        || e instanceof ArtifactResolutionException || e instanceof NativeRuntimeException
                        || e instanceof ArtifactIntegrityException
                        // audit 2026-10-07 (#147): a worker-child crash
                        // surfaces from provider.open() as InferenceException -
                        // without this the fallback chain never tries the next
                        // runtime after an open-phase crash
                        || e instanceof io.cortexj.error.InferenceException) {
                    last = asLoadFailure(e, candidate);
                    if (index < chain.size() - 1) {
                        java.util.Map<String, String> fallbackAttributes =
                                new java.util.LinkedHashMap<String, String>();
                        fallbackAttributes.put("model", plan.modelId());
                        fallbackAttributes.put("runtime", candidate.runtimeId());
                        platform.events().publish(PlatformEvent.Type.FALLBACK_APPLIED,
                                PlatformEvent.Level.WARNING,
                                "load failed on " + candidate.runtimeId() + ": " + e.getMessage()
                                        + "; trying fallback", fallbackAttributes);
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

    /**
     * audit 2026-10-07 (#98): the raw remote API key leaves core only through
     * the sealed RuntimeOpenRequest accessor, and only for the built-in
     * openai-remote runtime. Third-party providers receive a null credential
     * and must source their own secrets from runtime options or environment.
     */
    private String credentialFor(RuntimeProvider provider) {
        try {
            if (provider != null && provider.id() != null
                    && "openai-remote".equals(provider.id().value())) {
                return platform.config().remoteApiKey;
            }
        } catch (RuntimeException ignored) {
            // a provider id that cannot even be read gets no credential
        }
        return null;
    }

    /**
     * audit 2026-10-07 (#147): trust/license refusals carry a policy code
     * that no other runtime in the chain can satisfy.
     */
    private static boolean isPolicyRefusal(RuntimeException e) {
        if (e instanceof CortexJException) {
            String code = ((CortexJException) e).code();
            return ErrorCode.SEC_POLICY_BLOCKED.equals(code)
                    || ErrorCode.SEC_LICENSE_BLOCKED.equals(code);
        }
        return false;
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

    // audit 2026-10-08: teardown paths catch Throwable - an Error
    // mid-teardown must not strand the remaining handles/pins
    private static void closeAfterCancellation(RuntimeModel model, ModelRuntime runtime) {
        if (model != null) {
            try {
                model.close();
            } catch (Throwable closeError) {
                LOG.log(java.util.logging.Level.FINE,
                        "model close failed during load cancellation; continuing", closeError);
            }
        }
        if (runtime != null) {
            try {
                runtime.close();
            } catch (Throwable closeError) {
                LOG.log(java.util.logging.Level.FINE,
                        "runtime close failed during load cancellation; continuing", closeError);
            }
        }
    }

    private static void closeHandleAfterCancellation(ModelHandle handle) {
        try {
            handle.close();
        } catch (Throwable closeError) {
            LOG.log(java.util.logging.Level.FINE,
                    "handle close failed after load cancellation; continuing", closeError);
        }
    }

    private DefaultModelHandle loadCandidate(ExecutionPlan plan, ExecutionCandidate candidate, ExecutionRequest request,
                                             java.util.concurrent.atomic.AtomicBoolean cancelFlag) {
        platform.events().publish(PlatformEvent.Type.MODEL_RESOLUTION_STARTED,
                PlatformEvent.Level.INFO,
                "loading " + plan.modelId() + " via " + candidate.runtimeId(),
                candidateAttributes(plan, candidate));

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
                releasePin(closedHandle);
            }
        });
        handle.lifecycleTo(ModelLifecycleState.RESOLVING);
        try {
            enforceTrustPolicy(resolved);

            Path artifactPath = ensureArtifact(plan, resolved, candidate, handle, cancelFlag);

            if (cancelFlag != null && cancelFlag.get()) {
                throw new java.util.concurrent.CancellationException(
                        "load cancelled after artifact resolution");
            }

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
                    platform.events().asListener(),
                    credentialFor(provider)));

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
                    LOG.log(java.util.logging.Level.FINE,
                            "runtime close failed during load failure; continuing", closeError);
                }
                throw e instanceof ModelLoadException ? e : new ModelLoadException(ErrorCode.LOAD_FAILED,
                        "runtime " + candidate.runtimeId() + " failed to load the model: " + e.getMessage(), e);
            } catch (Error e) {
                // audit #102: an Error (e.g. OOM while loading a multi-GB model)
                // must not leak the runtime - for llamacpp that is a live
                // llama-server holding a refcount; best-effort close, then rethrow
                try {
                    runtime.close();
                } catch (Throwable closeError) {
                    // never mask the original Error with a cleanup failure
                }
                throw e;
            }
            if (cancelFlag != null && cancelFlag.get()) {
                closeAfterCancellation(model, runtime);
                throw new java.util.concurrent.CancellationException(
                        "load cancelled during " + candidate.runtimeId() + " load");
            }
            handle.attach(model, runtime);

            acquirePin(handle, resolved, candidate);

            platform.events().publish(PlatformEvent.Type.MODEL_LOADED,
                    PlatformEvent.Level.INFO,
                    "model loaded via " + candidate.runtimeId() + " on " + candidate.device(),
                    candidateAttributes(plan, candidate));

            openHandles.add(handle);
            return handle;
        } catch (RuntimeException e) {
            releasePin(handle);
            handle.lifecycleTo(ModelLifecycleState.FAILED);
            throw e;
        } catch (Error e) {
            // audit #102: release the (never-attached) pin and rethrow; the
            // runtime itself was already closed by the inner handler.
            // audit 2026-10-08: mark FAILED so the handle never strands in a
            // non-terminal state (fallback stays fail-fast for Error by
            // design - retrying the next candidate after an OOM risks
            // cascading the host).
            releasePin(handle);
            try {
                handle.lifecycleTo(ModelLifecycleState.FAILED);
            } catch (RuntimeException transitionFailure) {
                // the state machine must not mask the original Error
            }
            throw e;
        }
    }

    private static java.util.Map<String, String> candidateAttributes(ExecutionPlan plan,
                                                                     ExecutionCandidate candidate) {
        // audit #101: lifecycle events carry model/artifact attributes so OTel
        // spans key per load instead of collapsing every load onto ":"
        java.util.Map<String, String> attributes = new java.util.LinkedHashMap<String, String>();
        attributes.put("model", plan.modelId());
        attributes.put("runtime", candidate.runtimeId());
        if (candidate.artifact() != null) {
            attributes.put("artifact", candidate.artifact().id());
        }
        return attributes;
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
                                DefaultModelHandle handle, java.util.concurrent.atomic.AtomicBoolean cancelFlag) {
        ModelArtifact artifact = candidate.artifact();
        if (artifact == null || artifact.format() == io.cortexj.model.ArtifactFormat.REMOTE
                || candidate.device() == DeviceType.REMOTE) {
            return null;
        }
        if (candidate.requiresConversion()) {
            return materializeConversion(plan, resolved, candidate, handle, cancelFlag);
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
            Path cached;
            try {
                cached = cache.get(resolved.repository().value(), resolved.revision(), artifact.id(), checksum);
            } catch (ArtifactIntegrityException corrupted) {
                cached = null;
            }
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
                    resolved.revision(), cache, handle, cancelFlag);
        }
        throw new ArtifactResolutionException(ErrorCode.ARTIFACT_MISSING,
                "Resolved model does not contain artifact " + artifact.id(),
                "The selected artifact vanished during resolution; re-run the plan.")
                .detail("artifact", artifact.id());
    }

    Path oncePerKey(String key, java.util.function.Supplier<Path> work) {
        return oncePerKey(key, work, null, 1);
    }

    Path oncePerKey(String key, java.util.function.Supplier<Path> work,
                    java.util.concurrent.atomic.AtomicBoolean cancelFlag) {
        return oncePerKey(key, work, cancelFlag, 1);
    }

    private Path oncePerKey(String key, java.util.function.Supplier<Path> work,
                            java.util.concurrent.atomic.AtomicBoolean cancelFlag, int retries) {
        java.util.concurrent.CompletableFuture<Path> claim =
                new java.util.concurrent.CompletableFuture<Path>();
        java.util.concurrent.CompletableFuture<Path> inFlight = inFlightWork.putIfAbsent(key, claim);
        if (inFlight != null) {
            try {
                return awaitInFlight(inFlight, cancelFlag);
            } catch (ConcurrentClaimCancelled ownerCancelled) {
                // audit #97: the claim owner cancelled its own load - the claim is
                // already gone from the map, so re-claim with this caller's own
                // supplier instead of failing with somebody else's cancellation
                if (retries <= 0) {
                    throw new ModelLoadException(ErrorCode.LOAD_FAILED,
                            "A concurrent load of the same artifact was cancelled repeatedly",
                            "Retry the model load.");
                }
                return oncePerKey(key, work, cancelFlag, retries - 1);
            }
        }
        try {
            Path result = work.get();
            claim.complete(result);
            return result;
        } catch (RuntimeException failure) {
            claim.completeExceptionally(failure);
            throw failure;
        } catch (Error failure) {
            claim.completeExceptionally(failure);
            throw failure;
        } finally {
            inFlightWork.remove(key, claim);
        }
    }

    private static final class ConcurrentClaimCancelled extends RuntimeException {

        ConcurrentClaimCancelled() {
            super("concurrent claim cancelled by its owner");
        }
    }

    private Path downloadOnce(ResolvedArtifact resolvedArtifact, String repositoryId, String revision,
                               io.cortexj.cache.CacheStorage cache, final DefaultModelHandle handle,
                               final java.util.concurrent.atomic.AtomicBoolean cancelFlag) {
        String key = repositoryId + "~" + revision + "~" + resolvedArtifact.artifact().id();
        return oncePerKey(key, new java.util.function.Supplier<Path>() {
            @Override
            public Path get() {
                Path cached;
                try {
                    cached = cache.get(repositoryId, revision, resolvedArtifact.artifact().id(),
                            resolvedArtifact.artifact().checksum());
                } catch (ArtifactIntegrityException corrupted) {
                    cached = null;
                }
                if (cached != null) {
                    return cached;
                }
                handle.lifecycleTo(ModelLifecycleState.DOWNLOADING);
                return platform.downloads().download(resolvedArtifact, repositoryId,
                        revision, cache, platform.config().maxArtifactBytes, cancelFlag);
            }
        }, cancelFlag);
    }

    private Path awaitInFlight(java.util.concurrent.CompletableFuture<Path> inFlight,
                                java.util.concurrent.atomic.AtomicBoolean cancelFlag) {
        long timeoutMillis = awaitTimeoutMillis();
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (true) {
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            if (remainingMillis <= 0) {
                throw new ModelLoadException(ErrorCode.LOAD_FAILED,
                        "Timed out after " + timeoutMillis
                                + "ms waiting for a concurrent download of the same artifact",
                        "Retry the model load, or raise the cortexj.load.awaitTimeoutMillis system property.");
            }
            try {
                return inFlight.get(Math.min(remainingMillis, 250L), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ModelLoadException(ErrorCode.LOAD_FAILED,
                        "Interrupted while waiting for a concurrent download of the same artifact",
                        "Retry the model load.");
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof java.util.concurrent.CancellationException) {
                    throw new ConcurrentClaimCancelled();
                }
                if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                }
                throw new ModelLoadException(ErrorCode.LOAD_FAILED,
                        "Concurrent download of the same artifact failed: " + cause.getMessage(), cause);
            } catch (java.util.concurrent.CancellationException cancelledClaim) {
                // a CompletableFuture completed with a CancellationException throws
                // it directly from get() (no ExecutionException wrapper)
                throw new ConcurrentClaimCancelled();
            } catch (java.util.concurrent.TimeoutException sliceElapsed) {
                // audit #97: waiters stay responsive to their own cancellation
                // instead of blocking for the full await timeout
                if (cancelFlag != null && cancelFlag.get()) {
                    throw new java.util.concurrent.CancellationException(
                            "load cancelled while waiting for a concurrent download");
                }
            }
        }
    }

    private static long awaitTimeoutMillis() {
        String property = System.getProperty("cortexj.load.awaitTimeoutMillis");
        if (property == null || property.trim().isEmpty()) {
            return DEFAULT_AWAIT_TIMEOUT_MILLIS;
        }
        try {
            long parsed = Long.parseLong(property.trim());
            return parsed > 0 ? parsed : DEFAULT_AWAIT_TIMEOUT_MILLIS;
        } catch (NumberFormatException invalid) {
            return DEFAULT_AWAIT_TIMEOUT_MILLIS;
        }
    }

    private void enforceTrustPolicy(ResolvedModel resolved) {
        io.cortexj.model.TrustPolicy policy = platform.config().trustPolicy;
        String trustNote = resolved.descriptor().trustNote();
        if (trustNote == null || trustNote.isEmpty()) {
            // audit 2026-10-07 (#143): an absent note used to mean "trusted"
            // for every provider - but the local provider never sets one, so
            // an unchecked local file bypassed TRUSTED_ONLY entirely. A note
            // stays sufficient for blocking; without one, TRUSTED_ONLY
            // additionally requires at least one artifact checksum.
            if (policy == io.cortexj.model.TrustPolicy.TRUSTED_ONLY && !hasAnyChecksum(resolved)) {
                throw new ModelLoadException(io.cortexj.ErrorCode.SEC_POLICY_BLOCKED,
                        "Model " + resolved.descriptor().canonicalId()
                                + " carries no trust note and no artifact checksum, and the platform "
                                + "runs with TRUSTED_ONLY.",
                        "Record checksums in the local manifest (cortexj-model.json) so the "
                                + "artifact is verifiable, pin a trusted revision, or relax the "
                                + "trust policy to ALLOW_UNSIGNED_WITH_WARNING.");
            }
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

    private static boolean hasAnyChecksum(ResolvedModel resolved) {
        if (resolved.artifacts() == null) {
            return false;
        }
        for (ResolvedArtifact artifact : resolved.artifacts()) {
            if (artifact != null && artifact.artifact() != null
                    && artifact.artifact().checksum() != null
                    && artifact.artifact().checksum().value() != null
                    && !artifact.artifact().checksum().value().trim().isEmpty()
                    // audit 2026-10-08: an MD5 manifest must not satisfy
                    // TRUSTED_ONLY here only to die later with an untyped
                    // IllegalArgumentException from verify
                    && isTrustedAlgorithm(artifact.artifact().checksum().algorithm())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTrustedAlgorithm(String algorithm) {
        if (algorithm == null) {
            return false;
        }
        String normalized = algorithm.trim().toLowerCase(java.util.Locale.ROOT);
        return !normalized.equals("md5") && !normalized.equals("sha1") && !normalized.equals("sha-1");
    }

    private Path materializeConversion(ExecutionPlan plan, ResolvedModel resolved, ExecutionCandidate candidate,
                                        DefaultModelHandle handle,
                                        java.util.concurrent.atomic.AtomicBoolean cancelFlag) {
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
                        repositoryId, revision, convertedId, cache, handle, cancelFlag);
            }
        }, cancelFlag);
    }

    private Path runConversion(ExecutionPlan plan, ResolvedModel resolved, ExecutionCandidate candidate,
                               io.cortexj.planner.ConversionStep step, ResolvedArtifact source,
                               String repositoryId, String revision, String convertedId,
                               io.cortexj.cache.CacheStorage cache, DefaultModelHandle handle,
                               java.util.concurrent.atomic.AtomicBoolean cancelFlag) {
        Path sourcePath = ensureSourceArtifact(resolved, source, handle, cancelFlag);
        if (cancelFlag != null && cancelFlag.get()) {
            // audit #120: cancellation must stop before the multi-GB conversion
            throw new java.util.concurrent.CancellationException(
                    "load cancelled before conversion of " + source.artifact().id());
        }
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
            if (cancelFlag != null && cancelFlag.get()) {
                // audit #120: stop before committing a cancelled conversion to the cache
                throw new java.util.concurrent.CancellationException(
                        "load cancelled after conversion of " + source.artifact().id());
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

    private Path ensureSourceArtifact(ResolvedModel resolved, ResolvedArtifact source, DefaultModelHandle handle,
                                      java.util.concurrent.atomic.AtomicBoolean cancelFlag) {
        String scheme = source.location() == null ? "" : source.location().getScheme();
        if (scheme.equals("file")) {
            return Paths.get(source.location());
        }
        io.cortexj.cache.CacheStorage cache = platform.cache();
        Path cached;
        try {
            cached = cache.get(resolved.repository().value(), resolved.revision(),
                    source.artifact().id(), source.artifact().checksum());
        } catch (ArtifactIntegrityException corrupted) {
            cached = null;
        }
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
        return downloadOnce(source, resolved.repository().value(), resolved.revision(), cache, handle, cancelFlag);
    }

    private io.cortexj.spi.conversion.ModelConverter converterById(String converterId) {
        for (io.cortexj.spi.conversion.ModelConverter converter : platform.converters()) {
            if (converter.id().value().equals(converterId)) {
                return converter;
            }
        }
        return null;
    }

    private static String sanitizePathSegment(String value) {
        String cleaned = value == null ? "unknown" : value.trim().toLowerCase(java.util.Locale.ROOT);
        cleaned = cleaned.replaceAll("[^a-z0-9._-]", "_");
        while (cleaned.startsWith(".") || cleaned.startsWith("-")) {
            cleaned = "_" + cleaned.substring(1);
        }
        if (cleaned.isEmpty() || cleaned.equals("..")) {
            cleaned = "unknown";
        }
        return cleaned;
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
                    .resolve("converted").resolve(sanitizePathSegment(resolved.repository().value()))
                    .resolve(sanitizePathSegment(resolved.revision()));
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
                    directory.resolve(sanitizePathSegment(candidate.artifact().id())
                            + ".provenance.properties"));
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
            // audit #89: the write lock guarantees no async task is mid-body here,
            // so every not-yet-complete async load is a queued task: fail its
            // future deterministically instead of leaving the client hanging
            for (java.util.concurrent.CompletableFuture<ModelHandle> pending : asyncLoads) {
                if (!pending.isDone()) {
                    pending.completeExceptionally(new ModelLoadException(ErrorCode.LOAD_FAILED,
                            "Model load rejected: the CortexJ platform is closed.",
                            "Create a new CortexJ instance to load models after shutdown."));
                }
            }
            asyncLoads.clear();
            for (DefaultModelHandle handle : openHandles) {
                try {
                    handle.close();
                } catch (Throwable failure) {
                    // audit 2026-10-08: an Error in one handle must not skip
                    // the remaining handles and their pin releases
                    LOG.log(java.util.logging.Level.FINE,
                            "handle close failed during platform shutdown; continuing", failure);
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
