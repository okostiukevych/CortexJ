package io.cortexj.core;

import io.cortexj.CortexJ;
import io.cortexj.CortexJBuilder;
import io.cortexj.diagnostics.Support;
import io.cortexj.diagnostics.SupportReport;
import io.cortexj.hardware.EnvironmentProfile;
import io.cortexj.hardware.HardwareProfile;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelReference;
import io.cortexj.observability.PlatformEvent;
import io.cortexj.planner.ExecutionPlan;
import io.cortexj.planner.ExecutionRequest;
import io.cortexj.planner.Planner;
import io.cortexj.runtime.RuntimeDescriptor;
import io.cortexj.spi.platform.PlatformModule;
import io.cortexj.spi.repository.ModelRepositoryProvider;
import io.cortexj.spi.repository.ResolutionContext;
import io.cortexj.spi.repository.TokenSupplier;
import io.cortexj.spi.runtime.RuntimeProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public final class DefaultCortexJ implements CortexJ {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(DefaultCortexJ.class.getName());

    private final Configuration resolvedConfiguration;
    private final ResolutionContext resolutionContext;
    private final EnvironmentSnapshot snapshot;
    private final PlatformModule platformModule;
    private final io.cortexj.core.EventBus events;
    private final List<RuntimeProvider> runtimeProviders;
    private final List<ModelRepositoryProvider> repositoryProviders;
    private final List<io.cortexj.spi.conversion.ModelConverter> converters;
    private final io.cortexj.cache.CacheStorage cache;
    private final java.util.concurrent.atomic.AtomicBoolean platformClosed =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final io.cortexj.core.download.DownloadManager downloadManager;
    private final io.cortexj.planner.internal.DefaultExecutionPlanner planner;
    private final io.cortexj.core.DefaultModels models;
    private final ExecutorService ioExecutor;
    private final boolean ownsExecutor;
    private final ExecutorService asyncExecutor;
    private final ExecutorService downloadExecutor;

    DefaultCortexJ(CortexJBuilder configuration) {
        java.lang.Thread shutdownHook = null;
        if (configuration.isShutdownHook()) {
            shutdownHook = new java.lang.Thread(new Runnable() {
                @Override
                public void run() {
                    close();
                }
            }, "cortexj-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        }
        ExecutorService ioExecutorCandidate = null;
        ExecutorService asyncExecutorCandidate = null;
        java.util.concurrent.ThreadPoolExecutor downloadExecutorCandidate = null;
        try {
            this.snapshot = EnvironmentSnapshot.probe();
            PlatformModuleSelector.validateStarters(snapshot);
            this.platformModule = PlatformModuleSelector.select(snapshot);
            this.events = new EventBus(configuration.eventListeners(), configuration.isAsyncEventDelivery());

            this.runtimeProviders = SpiRegistries.loadSorted(RuntimeProvider.class,
                    new SpiRegistries.IdAccessor<RuntimeProvider>() {
                        public String idOf(RuntimeProvider provider) {
                            return provider.id().value();
                        }
                    }, configuration.runtimeProviders());

            this.repositoryProviders = SpiRegistries.loadSorted(ModelRepositoryProvider.class,
                    new SpiRegistries.IdAccessor<ModelRepositoryProvider>() {
                        public String idOf(ModelRepositoryProvider provider) {
                            return provider.id().value();
                        }
                    }, configuration.repositoryProviders());

            this.converters = SpiRegistries.loadSorted(io.cortexj.spi.conversion.ModelConverter.class,
                    new SpiRegistries.IdAccessor<io.cortexj.spi.conversion.ModelConverter>() {
                        public String idOf(io.cortexj.spi.conversion.ModelConverter converter) {
                            return converter.id().value();
                        }
                    }, configuration.converters());

            Configuration resolved = Configuration.from(configuration, snapshot);
            this.cache = new io.cortexj.cache.CacheStorage(
                    resolved.workingDirectory.resolve("cache"),
                    resolved.memoryLimitBytes == null || resolved.memoryLimitBytes <= 0
                            ? io.cortexj.cache.CacheStorage.DEFAULT_MAX_SIZE_BYTES
                            : Math.min(resolved.memoryLimitBytes * 4,
                                    io.cortexj.cache.CacheStorage.DEFAULT_MAX_SIZE_BYTES * 4));
            this.cache.setEventListener(events.asListener());

            if (configuration.executor() != null) {
                ioExecutorCandidate = configuration.executor();
                this.ownsExecutor = false;
            } else {
                ioExecutorCandidate = platformModule.createIoExecutor();
                this.ownsExecutor = true;
            }
            asyncExecutorCandidate = java.util.concurrent.Executors.newFixedThreadPool(
                    Math.max(2, Runtime.getRuntime().availableProcessors()),
                    io.cortexj.spi.platform.PlatformModule.daemonThreadFactory("cortexj-async"));
            int downloadWidth = positiveIntProperty("cortexj.download.width",
                    Math.max(4, Runtime.getRuntime().availableProcessors()));
            int downloadQueue = positiveIntProperty("cortexj.download.queue", 64);
            downloadExecutorCandidate = new java.util.concurrent.ThreadPoolExecutor(
                    downloadWidth, downloadWidth, 60L, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<Runnable>(downloadQueue),
                    io.cortexj.spi.platform.PlatformModule.daemonThreadFactory("cortexj-download"),
                    new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
            downloadExecutorCandidate.allowCoreThreadTimeOut(true);
            this.ioExecutor = ioExecutorCandidate;
            this.asyncExecutor = asyncExecutorCandidate;
            this.downloadExecutor = downloadExecutorCandidate;

            this.downloadManager = new io.cortexj.core.download.DownloadManager(events,
                    resolved.proxyHost, resolved.proxyPort);

            io.cortexj.spi.policy.PlanningContext.CacheLookup cacheLookup = new io.cortexj.spi.policy.PlanningContext.CacheLookup() {
                @Override
                public boolean isCached(String repositoryId, String revision, String artifactId, String checksum) {
                    return cache.contains(repositoryId, revision, artifactId);
                }

                @Override
                public java.nio.file.Path cachedArtifactPath(String repositoryId, String revision,
                                                             String artifactId, String checksum) {
                    java.nio.file.Path path = cache.artifactPath(repositoryId, revision, artifactId);
                    return java.nio.file.Files.isRegularFile(path) ? path : null;
                }
            };

            java.util.List<io.cortexj.spi.policy.ExecutionPolicy> policies =
                    new java.util.ArrayList<io.cortexj.spi.policy.ExecutionPolicy>(
                            configuration.executionPolicies());
            io.cortexj.planner.policy.EnterpriseRuntimePolicy enterprisePolicy =
                    loadEnterprisePolicy(resolved.workingDirectory);
            if (enterprisePolicy != null) {
                policies.add(enterprisePolicy);
                LOG.info("cortexj: enterprise runtime policy applied from "
                        + enterprisePolicyFile(resolved.workingDirectory));
            }

            this.planner = new io.cortexj.planner.internal.DefaultExecutionPlanner(
                    new io.cortexj.planner.internal.PlannerInputs(
                            runtimeProviders,
                            repositoryProviders,
                            converters,
                            policies,
                            new io.cortexj.model.artifact.ArtifactInspectorRegistry(),
                            snapshot.environment,
                            snapshot.hardware,
                            cacheLookup,
                            configuration.tokenSupplier() == null
                                    ? io.cortexj.spi.repository.TokenSupplier.none()
                                    : configuration.tokenSupplier(),
                            ioExecutorCandidate,
                            events.asListener(),
                            resolved.offline,
                            resolved.remoteRuntimeAllowed,
                            CortexJInfo.VERSION,
                            resolved.memoryLimitBytes == null ? 0L : resolved.memoryLimitBytes,
                            resolved.runtimeConfiguration(),
                            loadBenchmarkHints(resolved.workingDirectory)));

            this.models = new DefaultModels(this);
            this.resolvedConfiguration = resolved;
            this.resolutionContext = ResolutionContext.create(resolved.offline,
                    configuration.tokenSupplier(), ioExecutorCandidate, events.asListener());
            events.info(PlatformEvent.Type.PLATFORM_STARTED,
                    "CortexJ started on Java " + snapshot.environment.jvmFeatureVersion()
                            + " with " + runtimeProviders.size() + " runtime provider(s)");
        } catch (RuntimeException failure) {
            if (shutdownHook != null) {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException alreadyShuttingDown) {
                }
            }
            shutdownExecutor(ioExecutorCandidate);
            shutdownExecutor(asyncExecutorCandidate);
            shutdownExecutor(downloadExecutorCandidate);
            throw failure;
        }
    }

    List<io.cortexj.spi.conversion.ModelConverter> converters() {
        return converters;
    }

    Configuration config() {
        return resolvedConfiguration;
    }

    EnvironmentSnapshot snapshot() {
        return snapshot;
    }

    EventBus events() {
        return events;
    }

    io.cortexj.cache.CacheStorage cache() {
        return cache;
    }

    io.cortexj.core.download.DownloadManager downloads() {
        return downloadManager;
    }

    ExecutorService executor() {
        return ioExecutor;
    }

    ExecutorService asyncExecutor() {
        return asyncExecutor;
    }

    ExecutorService downloadExecutor() {
        return downloadExecutor;
    }

    List<RuntimeProvider> runtimeProviders() {
        return runtimeProviders;
    }

    @Override
    public io.cortexj.Models models() {
        return models;
    }

    @Override
    public Planner planner() {
        return new Planner() {
            @Override
            public ExecutionPlan plan(ExecutionRequest request) {
                return planner.plan(request);
            }
        };
    }

    @Override
    public Support support() {
        return new Support() {
            @Override
            public SupportReport inspect(ModelReference reference) {
                return planner.inspect(ExecutionRequest.forModel(reference));
            }

            @Override
            public SupportReport inspect(ExecutionRequest request) {
                return planner.inspect(request);
            }
        };
    }

    @Override
    public EnvironmentProfile environment() {
        return snapshot.environment;
    }

    @Override
    public HardwareProfile hardware() {
        return snapshot.hardware;
    }

    @Override
    public List<RuntimeDescriptor> runtimes() {
        List<RuntimeDescriptor> descriptors = new ArrayList<RuntimeDescriptor>();
        for (RuntimeProvider provider : runtimeProviders) {
            descriptors.add(provider.descriptor());
        }
        return descriptors;
    }

    @Override
    public java.util.List<io.cortexj.runtime.RuntimeHealthReport> runtimeHealth() {
        java.util.List<io.cortexj.runtime.RuntimeHealthReport> reports =
                new ArrayList<io.cortexj.runtime.RuntimeHealthReport>();
        for (RuntimeProvider provider : runtimeProviders) {
            io.cortexj.spi.runtime.RuntimeProbeResult result;
            try {
                result = planner.probeShared(provider);
            } catch (RuntimeException probeFailure) {
                result = io.cortexj.spi.runtime.RuntimeProbeResult.unavailable(
                        "probe failed: " + probeFailure.getMessage());
            }
            reports.add(new io.cortexj.runtime.RuntimeHealthReport(
                    provider.id(), result.health(), result.message()));
        }
        planner.invalidateProbeCache();
        return reports;
    }

    @Override
    public String platformModule() {
        return platformModule.name();
    }

    ResolutionContext resolutionContext() {
        return resolutionContext;
    }

    @Override
    public void close() {
        if (!platformClosed.compareAndSet(false, true)) {
            return;
        }
        models.closeAll();
        events.info(PlatformEvent.Type.PLATFORM_CLOSED, "CortexJ shutting down");
        events.close();
        shutdownExecutor(ownsExecutor ? ioExecutor : null);
        shutdownExecutor(asyncExecutor);
        shutdownExecutor(downloadExecutor);
    }

    private static io.cortexj.planner.benchmark.BenchmarkHints loadBenchmarkHints(
            java.nio.file.Path workingDirectory) {
        String explicit = System.getProperty(io.cortexj.planner.benchmark.BenchmarkHints.SYSPROP);
        java.nio.file.Path file = explicit != null && !explicit.trim().isEmpty()
                ? java.nio.file.Paths.get(explicit.trim())
                : workingDirectory.resolve(io.cortexj.planner.benchmark.BenchmarkHints.FILE_NAME);
        if (!java.nio.file.Files.isRegularFile(file)) {
            return io.cortexj.planner.benchmark.BenchmarkHints.empty();
        }
        try {
            io.cortexj.planner.benchmark.BenchmarkHints hints =
                    io.cortexj.planner.benchmark.BenchmarkHints.fromFile(file);
            if (!hints.warnings().isEmpty()) {
                LOG.warning("cortexj: benchmark hints file " + file
                        + " has ignored entries: " + hints.warnings());
            }
            return hints;
        } catch (java.io.IOException unreadable) {
            LOG.warning("cortexj: benchmark hints file " + file
                    + " could not be read and was NOT applied: " + unreadable);
            return io.cortexj.planner.benchmark.BenchmarkHints.empty();
        }
    }

    private static java.nio.file.Path enterprisePolicyFile(java.nio.file.Path workingDirectory) {
        String explicit = System.getProperty(io.cortexj.planner.policy.EnterpriseRuntimePolicy.SYSPROP);
        if (explicit != null && !explicit.trim().isEmpty()) {
            return java.nio.file.Paths.get(explicit.trim());
        }
        return workingDirectory.resolve(io.cortexj.planner.policy.EnterpriseRuntimePolicy.FILE_NAME);
    }

    private static io.cortexj.planner.policy.EnterpriseRuntimePolicy loadEnterprisePolicy(
            java.nio.file.Path workingDirectory) {
        java.nio.file.Path file = enterprisePolicyFile(workingDirectory);
        if (!java.nio.file.Files.isRegularFile(file)) {
            return null;
        }
        try {
            io.cortexj.planner.policy.EnterpriseRuntimePolicy policy =
                    io.cortexj.planner.policy.EnterpriseRuntimePolicy.fromFile(file);
            if (!policy.isRestrictive()) {
                LOG.warning("cortexj: enterprise runtime policy file " + file
                        + " is present but declares no restrictions; ignoring it");
                return null;
            }
            return policy;
        } catch (java.io.IOException unreadable) {
            LOG.warning("cortexj: enterprise runtime policy file " + file
                    + " could not be read and was NOT applied: " + unreadable);
            return null;
        }
    }

    private static int positiveIntProperty(String key, int fallback) {
        String raw = System.getProperty(key);
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static void shutdownExecutor(ExecutorService executor) {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
