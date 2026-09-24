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

    private final CortexJBuilder configuration;
    private final EnvironmentSnapshot snapshot;
    private final PlatformModule platformModule;
    private final io.cortexj.core.EventBus events;
    private final List<RuntimeProvider> runtimeProviders;
    private final List<ModelRepositoryProvider> repositoryProviders;
    private final io.cortexj.cache.CacheStorage cache;
    private final io.cortexj.core.download.DownloadManager downloadManager;
    private final io.cortexj.planner.internal.DefaultExecutionPlanner planner;
    private final io.cortexj.core.DefaultModels models;
    private final ExecutorService ioExecutor;
    private final boolean ownsExecutor;

    DefaultCortexJ(CortexJBuilder configuration) {
        this.configuration = configuration;
        this.snapshot = EnvironmentSnapshot.probe();
        PlatformModuleSelector.validateStarters(snapshot);
        this.platformModule = PlatformModuleSelector.select(snapshot);
        this.events = new EventBus(configuration.eventListeners());

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

        Configuration resolved = Configuration.from(configuration, snapshot);
        this.cache = new io.cortexj.cache.CacheStorage(
                resolved.workingDirectory.resolve("cache"),
                resolved.memoryLimitBytes == null || resolved.memoryLimitBytes <= 0
                        ? io.cortexj.cache.CacheStorage.DEFAULT_MAX_SIZE_BYTES
                        : Math.min(resolved.memoryLimitBytes * 4,
                                io.cortexj.cache.CacheStorage.DEFAULT_MAX_SIZE_BYTES * 4));
        this.cache.setEventListener(events.asListener());

        if (configuration.executor() != null) {
            this.ioExecutor = configuration.executor();
            this.ownsExecutor = false;
        } else {
            this.ioExecutor = platformModule.createIoExecutor();
            this.ownsExecutor = true;
        }

        this.downloadManager = new io.cortexj.core.download.DownloadManager(events);

        io.cortexj.spi.policy.PlanningContext.CacheLookup cacheLookup = new io.cortexj.spi.policy.PlanningContext.CacheLookup() {
            @Override
            public boolean isCached(String repositoryId, String revision, String artifactId, String checksum) {
                return cache.contains(repositoryId, revision, artifactId);
            }
        };

        this.planner = new io.cortexj.planner.internal.DefaultExecutionPlanner(
                new io.cortexj.planner.internal.PlannerInputs(
                        runtimeProviders,
                        repositoryProviders,
                        new ArrayList<io.cortexj.spi.conversion.ModelConverter>(),
                        configuration.executionPolicies(),
                        null,
                        snapshot.environment,
                        snapshot.hardware,
                        cacheLookup,
                        resolved.offline,
                        resolved.remoteRuntimeAllowed,
                        "0.1.0",
                        resolved.memoryLimitBytes == null ? 0L : resolved.memoryLimitBytes));

        this.models = new DefaultModels(this);
        events.info(PlatformEvent.Type.PLATFORM_STARTED,
                "CortexJ started on Java " + snapshot.environment.jvmFeatureVersion()
                        + " with " + runtimeProviders.size() + " runtime provider(s)");
    }

    Configuration config() {
        return Configuration.from(configuration, snapshot);
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

    ResolutionContext resolutionContext() {
        final CortexJBuilder captured = configuration;
        return ResolutionContext.create(config().offline, captured.tokenSupplier(), ioExecutor, events.asListener());
    }

    @Override
    public void close() {
        models.closeAll();
        events.info(PlatformEvent.Type.PLATFORM_CLOSED, "CortexJ shutting down");
        if (ownsExecutor) {
            ioExecutor.shutdown();
            try {
                if (!ioExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    ioExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                ioExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
