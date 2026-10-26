package io.cortexj.core;

import io.cortexj.ErrorCode;
import io.cortexj.error.ArtifactIntegrityException;
import io.cortexj.error.ArtifactResolutionException;
import io.cortexj.error.CortexJException;
import io.cortexj.error.InsufficientMemoryException;
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
    // audit 2026-10-10: fixed stripe locks instead of a per-identity lock
    // map - the map used to grow without bound over a long-lived process
    // (one Object per unique model path ever pinned) while a striped array
    // keeps the #94 acquire/release serialization with O(1) memory
    private static final int PIN_LOCK_STRIPES = 16;
    private final Object[] pinLocks = pinLockStripes();

    private static Object[] pinLockStripes() {
        Object[] stripes = new Object[PIN_LOCK_STRIPES];
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new Object();
        }
        return stripes;
    }
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
        return pinLocks[(identity.hashCode() & 0x7fffffff) % PIN_LOCK_STRIPES];
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
            final java.util.concurrent.atomic.AtomicReference<Thread> workerThread =
                    new java.util.concurrent.atomic.AtomicReference<Thread>();
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
                            workerThread.set(Thread.currentThread());
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
                            workerThread.set(null);
                            Thread.interrupted();
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
            return new CancellableLoad(underlying, cancelFlag, workerThread);
        } finally {
            loadLock.readLock().unlock();
        }
    }

    private static final class CancellableLoad implements java.util.concurrent.Future<ModelHandle> {

        private final java.util.concurrent.CompletableFuture<ModelHandle> underlying;
        private final java.util.concurrent.atomic.AtomicBoolean cancelFlag;
        private final java.util.concurrent.atomic.AtomicReference<Thread> workerThread;

        CancellableLoad(java.util.concurrent.CompletableFuture<ModelHandle> underlying,
                        java.util.concurrent.atomic.AtomicBoolean cancelFlag,
                        java.util.concurrent.atomic.AtomicReference<Thread> workerThread) {
            this.underlying = underlying;
            this.cancelFlag = cancelFlag;
            this.workerThread = workerThread;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelFlag.set(true);
            if (mayInterruptIfRunning) {
                Thread worker = workerThread.get();
                if (worker != null) {
                    worker.interrupt();
                }
            }
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
        List<ModelLoadException> failures = new ArrayList<ModelLoadException>();
        for (int index = 0; index < chain.size(); index++) {
            if ((cancelFlag != null && cancelFlag.get()) || Thread.currentThread().isInterrupted()) {
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
                if ((cancelFlag != null && cancelFlag.get()) || Thread.currentThread().isInterrupted()
                        || isCancelledCause(e)) {
                    throw e instanceof java.util.concurrent.CancellationException
                            ? e
                            : new java.util.concurrent.CancellationException(
                                    "load cancelled during candidate " + candidate.runtimeId());
                }
                // audit 2026-10-07 (#147): a trust/license refusal is
                // provider-independent - retrying the next runtime is
                // pointless, fail fast with the policy error instead
                if (isPolicyRefusal(e)) {
                    throw e instanceof ModelLoadException ? e : asLoadFailure(e, candidate);
                }
                if (e instanceof ModelLoadException || e instanceof RuntimeUnavailableException
                        || e instanceof ArtifactResolutionException || e instanceof NativeRuntimeException
                        || e instanceof ArtifactIntegrityException
                        // audit 2026-10-08 (#153): a load-time memory
                        // re-check failure should try the next (possibly
                        // smaller) candidate instead of failing the chain
                        || e instanceof InsufficientMemoryException
                        // audit 2026-10-07 (#147): a worker-child crash
                        // surfaces from provider.open() as InferenceException -
                        // without this the fallback chain never tries the next
                        // runtime after an open-phase crash
                        || e instanceof io.cortexj.error.InferenceException) {
                    last = asLoadFailure(e, candidate);
                    failures.add(last);
                    if (index < chain.size() - 1) {
                        platform.events().publish(PlatformEvent.Type.FALLBACK_APPLIED,
                                PlatformEvent.Level.WARNING,
                                "load failed on " + candidate.runtimeId() + ": " + e.getMessage()
                                        + "; trying fallback", candidateAttributes(plan, candidate));
                    }
                    continue;
                }
                throw e;
            }
        }
        if (failures.isEmpty()) {
            throw last != null ? last : new ModelLoadException(ErrorCode.LOAD_FAILED,
                    "No runtime could load model " + request.model().value(),
                    "Inspect the diagnostics with support().inspect() and verify runtimes.");
        }
        // audit 2026-10-10: the chain used to surface only the LAST error,
        // masking the root cause (a failed healthy local runtime hid behind
        // a degraded remote's 404). The first failure stays primary, every
        // later one is attached as suppressed so no candidate's error is lost.
        ModelLoadException primary = failures.get(0);
        for (int i = 1; i < failures.size(); i++) {
            primary.addSuppressed(failures.get(i));
        }
        throw primary;
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

    private static boolean isCancelledCause(Throwable error) {
        Throwable cursor = error;
        int depth = 0;
        while (cursor != null && depth++ < 16) {
            if (cursor instanceof java.util.concurrent.CancellationException) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    /**
     * audit 2026-10-08 (#153): fail-before-load with current host numbers.
     */
    private void recheckMemory(ExecutionPlan plan, ExecutionCandidate candidate) {
        if (candidate.memoryEstimate() == null || !candidate.memoryEstimate().isKnown()) {
            return;
        }
        long available = platform.environment().availableMemoryEstimateBytes();
        if (available <= 0) {
            available = platform.environment().totalPhysicalMemoryBytes();
        }
        if (available <= 0) {
            return;
        }
        long estimated = candidate.memoryEstimate().totalBytes();
        if (candidate.memoryEstimate().confidence() == io.cortexj.planner.MemoryConfidence.LOW) {
            estimated = estimated + estimated / 2;
        }
        if (estimated > available) {
            throw new InsufficientMemoryException(ErrorCode.MEM_INSUFFICIENT,
                    "Model " + plan.modelId() + " no longer fits: needs ~" + estimated
                            + " bytes but only ~" + available + " are available now.",
                    "Free memory, pick a smaller quantization, or set an explicit memory limit.");
        }
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
            enforceTrustPolicy(plan, resolved, candidate);

            Path artifactPath = ensureArtifact(plan, resolved, candidate, handle, cancelFlag);

            // audit 2026-10-08 (#153): plan/load TOCTOU - re-check memory
            // right before the native open with fresh host numbers; LOW
            // confidence estimates are weighed up 1.5x instead of equally
            recheckMemory(plan, candidate);

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
                ensureTokenizerSidecars(plan, candidate, resolved, cached, handle, cancelFlag);
                return cached;
            }
            if (platform.config().offline) {
                throw new ArtifactResolutionException(ErrorCode.MODEL_OFFLINE_UNRESOLVED,
                        "Artifact " + artifact.id() + " is not cached and offline mode is active.",
                        "Pre-download the artifact with 'cortexj pull' or disable offline mode.")
                        .detail("artifact", artifact.id());
            }
            Path downloaded = downloadOnce(resolvedArtifact, resolved.repository().value(),
                    resolved.revision(), cache, handle, cancelFlag);
            ensureTokenizerSidecars(plan, candidate, resolved, downloaded, handle, cancelFlag);
            return downloaded;
        }
        throw new ArtifactResolutionException(ErrorCode.ARTIFACT_MISSING,
                "Resolved model does not contain artifact " + artifact.id(),
                "The selected artifact vanished during resolution; re-run the plan.")
                .detail("artifact", artifact.id());
    }

    /**
     * Real-model verification 2026-10-10 (prod gap): runtimes that need a
     * tokenizer (ONNX/DJL via HfTokenizer) look for {@code tokenizer.json}
     * next to the weight file, but a remote resolution only ever downloads
     * the planned weight - the resolved TOKENIZER sidecar stays on the
     * server. Without it the provider silently falls back to a hash
     * tokenizer whose ids fail inference (ORT Gather out-of-bounds on a
     * real MiniLM). After the weight is materialized inside the platform
     * cache, fetch the resolved {@code tokenizer.json} sidecar (cache hit,
     * download, or local file) and place it next to the weight.
     *
     * <p>Best-effort by design: a missing or unfetchable sidecar keeps the
     * previous fallback behavior (warn, continue) - models whose runtime
     * never reads a tokenizer (llamacpp, remote) must not fail because of
     * it. Never writes outside the platform cache directory, so
     * user-owned local files are left untouched (their siblings are
     * already on disk). Presence is re-checked on every load, so an
     * evicted sidecar self-heals on the next load.
     *
     * <p>Deliberate tradeoff (audit 2026-10-10): the staged sibling copy
     * is NOT credited to the cache size index (trackedBytes) - the source
     * sidecar is already accounted as a cache artifact, the copy is a
     * derivative bounded by the tokenizer size (~0.5 MB), and routing it
     * through cache accounting would double-count or require a new public
     * CacheStorage API. If the cache cap is hit, the copy is evictable
     * like any unlisted file and the next load re-stages it.
     */
    private void ensureTokenizerSidecars(ExecutionPlan plan, ExecutionCandidate candidate,
                                         ResolvedModel resolved, Path artifactPath,
                                         DefaultModelHandle handle,
                                         java.util.concurrent.atomic.AtomicBoolean cancelFlag) {
        if (resolved == null || resolved.artifacts() == null || artifactPath == null) {
            return;
        }
        io.cortexj.cache.CacheStorage cache = platform.cache();
        Path cacheRoot = cache == null ? null : cache.root();
        if (cacheRoot == null || !artifactPath.startsWith(cacheRoot)) {
            return;
        }
        Path parent = artifactPath.getParent();
        if (parent == null) {
            return;
        }
        Path target = parent.resolve("tokenizer.json");
        if (java.nio.file.Files.isRegularFile(target)) {
            return;
        }
        for (ResolvedArtifact sidecar : resolved.artifacts()) {
            if (sidecar == null || sidecar.artifact() == null
                    || sidecar.artifact().format() != io.cortexj.model.ArtifactFormat.TOKENIZER) {
                continue;
            }
            if (!"tokenizer.json".equals(sidecarFileName(sidecar.artifact().id()))) {
                continue;
            }
            Path source = resolveSidecarSource(sidecar, resolved, cache, handle, cancelFlag);
            if (source == null) {
                String note = "tokenizer.json for " + sidecar.artifact().id()
                        + " is not available; the runtime falls back to its built-in tokenizer";
                platform.events().publish(PlatformEvent.Type.MODEL_RESOLUTION_COMPLETED,
                        PlatformEvent.Level.WARNING, note, candidateAttributes(plan, candidate));
                handle.markDegradedTokenizer(note);
                return;
            }
            if (source.equals(target)) {
                return;
            }
            Path temp = parent.resolve("tokenizer.json.part-" + System.nanoTime()
                    + "-" + SIDECAR_TEMP_SEQUENCE.incrementAndGet());
            try {
                java.nio.file.Files.copy(source, temp,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                try {
                    java.nio.file.Files.move(temp, target,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (java.io.IOException atomicUnsupported) {
                    java.nio.file.Files.move(temp, target,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (java.io.IOException copyFailed) {
                try {
                    java.nio.file.Files.deleteIfExists(temp);
                } catch (java.io.IOException ignored) {
                    // best-effort cleanup of the staging temp
                }
                String note = "cannot stage tokenizer.json next to " + artifactPath.getFileName()
                        + ": " + copyFailed.getMessage()
                        + "; the runtime falls back to its built-in tokenizer";
                platform.events().publish(PlatformEvent.Type.MODEL_RESOLUTION_COMPLETED,
                        PlatformEvent.Level.WARNING, note, candidateAttributes(plan, candidate));
                handle.markDegradedTokenizer(note);
            }
            return;
        }
    }

    private static final java.util.concurrent.atomic.AtomicLong SIDECAR_TEMP_SEQUENCE =
            new java.util.concurrent.atomic.AtomicLong();

    private Path resolveSidecarSource(ResolvedArtifact sidecar, ResolvedModel resolved,
                                      io.cortexj.cache.CacheStorage cache,
                                      DefaultModelHandle handle,
                                      java.util.concurrent.atomic.AtomicBoolean cancelFlag) {
        if (sidecar.location() == null) {
            return null;
        }
        String scheme = sidecar.location().getScheme();
        if (scheme != null && scheme.equals("file")) {
            Path file = Paths.get(sidecar.location());
            return java.nio.file.Files.isRegularFile(file) ? file : null;
        }
        if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) {
            return null;
        }
        String repositoryId = resolved.repository().value();
        String revision = resolved.revision();
        Path cached;
        try {
            cached = cache.get(repositoryId, revision, sidecar.artifact().id(),
                    sidecar.artifact().checksum());
        } catch (ArtifactIntegrityException corrupted) {
            cached = null;
        }
        if (cached != null) {
            return cached;
        }
        if (platform.config().offline) {
            return null;
        }
        // audit 2026-10-10: a checksum-declared sidecar that fails
        // verification must fail the load, not degrade silently into a
        // mis-tokenizing fallback (even under TRUSTED_ONLY, which never
        // inspects sidecars). Checksum-less sidecars keep warn-and-continue.
        // Cancellation is rethrown so a cancelled load stays cancelled.
        try {
            return downloadOnce(sidecar, repositoryId, revision, cache, handle, cancelFlag);
        } catch (ArtifactIntegrityException integrity) {
            if (sidecar.artifact().checksum() != null) {
                throw integrity;
            }
            return null;
        } catch (RuntimeException downloadFailed) {
            if (cancelFlag != null && cancelFlag.get()) {
                throw downloadFailed;
            }
            return null;
        }
    }

    private static String sidecarFileName(String artifactId) {
        if (artifactId == null) {
            return "";
        }
        int slash = artifactId.lastIndexOf('/');
        return slash < 0 ? artifactId : artifactId.substring(slash + 1);
    }

    Path oncePerKey(String key, java.util.function.Supplier<Path> work) {
        return oncePerKey(key, work, null, 3);
    }

    Path oncePerKey(String key, java.util.function.Supplier<Path> work,
                    java.util.concurrent.atomic.AtomicBoolean cancelFlag) {
        return oncePerKey(key, work, cancelFlag, 3);
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
                // audit #97: the claim owner cancelled its own load - re-claim with
                // this caller's own supplier instead of failing with somebody else's
                // cancellation. The cancelled owner's finally-block may not have run
                // yet, so the stale entry can still sit in the map; drop it first
                // (value-guarded remove: never touches a newer claim published in
                // the meantime) or the re-claim would only observe the same dead
                // claim again and consume its retry.
                inFlightWork.remove(key, inFlight);
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
                if (isCancelledCause(cause)) {
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

    private void enforceTrustPolicy(ExecutionPlan plan, ResolvedModel resolved, ExecutionCandidate candidate) {
        io.cortexj.model.TrustPolicy policy = platform.config().trustPolicy;
        String trustNote = resolved.descriptor().trustNote();
        if (trustNote == null || trustNote.isEmpty()) {
            // audit 2026-10-07 (#143): an absent note used to mean "trusted"
            // for every provider - but the local provider never sets one, so
            // an unchecked local file bypassed TRUSTED_ONLY entirely. A note
            // stays sufficient for blocking; without one, TRUSTED_ONLY
            // additionally requires the selected weight artifact (or conversion
            // source) to carry a verified strong checksum.
            if (policy == io.cortexj.model.TrustPolicy.TRUSTED_ONLY
                    && !hasTrustedCandidateChecksum(resolved, candidate)) {
                throw new ModelLoadException(io.cortexj.ErrorCode.SEC_POLICY_BLOCKED,
                        "Model " + resolved.descriptor().canonicalId()
                                + " carries no trust note and no verified artifact checksum on the selected "
                                + "weight artifact, and the platform runs with TRUSTED_ONLY.",
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
            platform.events().publish(PlatformEvent.Type.MODEL_RESOLUTION_COMPLETED,
                    PlatformEvent.Level.WARNING,
                    "untrusted model loaded (trust policy allows it): " + trustNote,
                    candidateAttributes(plan, candidate));
        }
    }

    private static boolean hasTrustedCandidateChecksum(ResolvedModel resolved, ExecutionCandidate candidate) {
        if (candidate != null && candidate.artifact() != null
                && candidate.artifact().format() != io.cortexj.model.ArtifactFormat.REMOTE) {
            if (candidate.requiresConversion() && candidate.conversion() != null) {
                String sourceId = candidate.conversion().sourceArtifactId();
                if (resolved.artifacts() != null && sourceId != null) {
                    for (ResolvedArtifact resolvedArtifact : resolved.artifacts()) {
                        if (resolvedArtifact != null && resolvedArtifact.artifact() != null
                                && sourceId.equals(resolvedArtifact.artifact().id())) {
                            return hasTrustedChecksum(resolvedArtifact.artifact());
                        }
                    }
                }
                return false;
            }
            if (hasTrustedChecksum(candidate.artifact())) {
                return true;
            }
            if (resolved.artifacts() != null) {
                for (ResolvedArtifact resolvedArtifact : resolved.artifacts()) {
                    if (resolvedArtifact != null && resolvedArtifact.artifact() != null
                            && candidate.artifact().id().equals(resolvedArtifact.artifact().id())) {
                        return hasTrustedChecksum(resolvedArtifact.artifact());
                    }
                }
            }
            return false;
        }
        return hasAnyChecksum(resolved);
    }

    private static boolean hasTrustedChecksum(ModelArtifact artifact) {
        return artifact != null
                && artifact.checksum() != null
                && artifact.checksum().value() != null
                && !artifact.checksum().value().trim().isEmpty()
                && isTrustedAlgorithm(artifact.checksum().algorithm());
    }

    private static boolean hasAnyChecksum(ResolvedModel resolved) {
        if (resolved.artifacts() == null) {
            return false;
        }
        for (ResolvedArtifact artifact : resolved.artifacts()) {
            if (artifact != null && hasTrustedChecksum(artifact.artifact())) {
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
        return normalized.equals("sha256") || normalized.equals("sha-256")
                || normalized.equals("sha384") || normalized.equals("sha-384")
                || normalized.equals("sha512") || normalized.equals("sha-512");
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
            String sha256 = io.cortexj.cache.Checksums.sha256Hex(outputPath);
            Path cachedOutput = cache.adopt(outputPath, repositoryId, revision, convertedId, null);
            writeProvenance(resolved, candidate, step, converter, source, result, sha256);
            return cachedOutput;
        } catch (java.io.IOException e) {
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
                manifest.store(out, "CortexJ deterministic conversion provenance");
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
