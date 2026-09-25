package io.cortexj;

import io.cortexj.error.ConfigurationException;
import io.cortexj.spi.policy.ExecutionPolicy;
import io.cortexj.spi.repository.ModelRepositoryProvider;
import io.cortexj.spi.repository.TokenSupplier;
import io.cortexj.spi.runtime.RuntimeProvider;
import io.cortexj.observability.PlatformEventListener;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.ExecutorService;

public final class CortexJBuilder {

    private final List<RuntimeProvider> runtimeProviders = new ArrayList<RuntimeProvider>();
    private final List<ModelRepositoryProvider> repositoryProviders = new ArrayList<ModelRepositoryProvider>();
    private final List<ExecutionPolicy> executionPolicies = new ArrayList<ExecutionPolicy>();
    private final List<io.cortexj.spi.conversion.ModelConverter> converters =
            new ArrayList<io.cortexj.spi.conversion.ModelConverter>();
    private io.cortexj.model.TrustPolicy trustPolicy = io.cortexj.model.TrustPolicy.ALLOW_UNSIGNED_WITH_WARNING;
    private final List<PlatformEventListener> eventListeners = new ArrayList<PlatformEventListener>();

    private boolean offline = offlineFromEnvironment();
    private Path workingDirectory;
    private Long memoryLimitBytes;
    private TokenSupplier tokenSupplier;
    private String remoteBaseUrl;
    private String remoteApiKey;
    private String remoteModelPrefix;
    private ExecutorService executor;
    private boolean allowRemoteRuntime = true;

    public CortexJBuilder runtimeProvider(RuntimeProvider provider) {
        if (provider != null) {
            runtimeProviders.add(provider);
        }
        return this;
    }

    public CortexJBuilder repositoryProvider(ModelRepositoryProvider provider) {
        if (provider != null) {
            repositoryProviders.add(provider);
        }
        return this;
    }

    public CortexJBuilder executionPolicy(ExecutionPolicy policy) {
        if (policy != null) {
            executionPolicies.add(policy);
        }
        return this;
    }

    public CortexJBuilder eventListener(PlatformEventListener listener) {
        if (listener != null) {
            eventListeners.add(listener);
        }
        return this;
    }

    public CortexJBuilder offlineMode(boolean offline) {
        this.offline = offline;
        return this;
    }

    public CortexJBuilder workingDirectory(Path directory) {
        this.workingDirectory = directory;
        return this;
    }

    public CortexJBuilder memoryLimitBytes(long bytes) {
        this.memoryLimitBytes = Long.valueOf(bytes);
        return this;
    }

    public CortexJBuilder tokenSupplier(TokenSupplier supplier) {
        this.tokenSupplier = supplier;
        return this;
    }

    public CortexJBuilder remoteBaseUrl(String baseUrl) {
        this.remoteBaseUrl = baseUrl;
        return this;
    }

    public CortexJBuilder remoteApiKey(String apiKey) {
        this.remoteApiKey = apiKey;
        return this;
    }

    public CortexJBuilder remoteModelPrefix(String prefix) {
        this.remoteModelPrefix = prefix;
        return this;
    }

    public CortexJBuilder executor(ExecutorService executor) {
        this.executor = executor;
        return this;
    }

    public CortexJBuilder allowRemoteRuntime(boolean allow) {
        this.allowRemoteRuntime = allow;
        return this;
    }

    public CortexJBuilder trustPolicy(io.cortexj.model.TrustPolicy policy) {
        this.trustPolicy = policy == null ? io.cortexj.model.TrustPolicy.ALLOW_UNSIGNED_WITH_WARNING : policy;
        return this;
    }

    public List<RuntimeProvider> runtimeProviders() {
        return runtimeProviders;
    }

    public List<ModelRepositoryProvider> repositoryProviders() {
        return repositoryProviders;
    }

    public List<ExecutionPolicy> executionPolicies() {
        return executionPolicies;
    }

    public CortexJBuilder converter(io.cortexj.spi.conversion.ModelConverter converter) {
        if (converter != null) {
            converters.add(converter);
        }
        return this;
    }

    public List<io.cortexj.spi.conversion.ModelConverter> converters() {
        return converters;
    }

    public io.cortexj.model.TrustPolicy trustPolicy() {
        return trustPolicy;
    }

    public List<PlatformEventListener> eventListeners() {
        return eventListeners;
    }

    public boolean isOffline() {
        return offline;
    }

    public Path workingDirectory() {
        return workingDirectory;
    }

    public Long memoryLimitBytes() {
        return memoryLimitBytes;
    }

    public TokenSupplier tokenSupplier() {
        return tokenSupplier;
    }

    public String remoteBaseUrl() {
        return remoteBaseUrl;
    }

    public String remoteApiKey() {
        return remoteApiKey;
    }

    public String remoteModelPrefix() {
        return remoteModelPrefix;
    }

    public ExecutorService executor() {
        return executor;
    }

    public boolean isRemoteRuntimeAllowed() {
        return allowRemoteRuntime;
    }

    public CortexJ build() {
        for (PlatformFactory factory : ServiceLoader.load(PlatformFactory.class, CortexJBuilder.class.getClassLoader())) {
            return factory.create(this);
        }
        throw new ConfigurationException(ErrorCode.CONF_BOOTSTRAP_MISSING,
                "No CortexJ platform implementation found on the classpath.",
                "Add the 'cortexj-core' dependency, or a 'cortexj-starter-java<version>' starter artifact.");
    }

    private static boolean offlineFromEnvironment() {
        String property = System.getProperty("cortexj.offline");
        if (property != null) {
            return Boolean.parseBoolean(property.trim());
        }
        String env = System.getenv("CORTEXJ_OFFLINE");
        return env != null && Boolean.parseBoolean(env.trim());
    }
}
