package io.cortexj.springboot;

import io.cortexj.CortexJBuilder;
import io.cortexj.error.ConfigurationException;
import io.cortexj.model.TrustPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * {@code cortexj.*} property surface (audit 2026-10-21 follow-up). Field
 * defaults mirror the platform builder: remote runtimes stay allowed and
 * offline detection keeps the {@code cortexj.offline} system-property /
 * {@code CORTEXJ_OFFLINE} environment fallback the builder applies on its own.
 */
@ConfigurationProperties(prefix = "cortexj")
public class CortexJProperties {

    /** Model reference to auto-load, e.g. {@code qwen3/qwen3-4b}. */
    private String model;

    /** Disable repository/network planning entirely. */
    private Boolean offline;

    /** Whether remote (hosted) runtimes may satisfy inference, default true. */
    private Boolean allowRemoteRuntime = Boolean.TRUE;

    /** Cache / downloads working directory, default is the platform's own. */
    private String workingDirectory;

    /** Artifact verification policy name ({@link TrustPolicy}). */
    private String trustPolicy;

    /** Memory budget for loaded artifacts. */
    private Long memoryLimit;

    /** Largest artifact byte size accepted. */
    private Long maxArtifactBytes;

    /** Opt-in platform shutdown hook on JVM exit. */
    private Boolean shutdownHook;

    /** Remote-runtime endpoint configuration. */
    private final Remote remote = new Remote();

    /** Applies the configured values onto a fresh platform builder. */
    public CortexJBuilder apply(CortexJBuilder builder) {
        if (offline != null) {
            builder.offlineMode(offline);
        }
        if (allowRemoteRuntime != null) {
            builder.allowRemoteRuntime(allowRemoteRuntime);
        }
        if (workingDirectory != null && !workingDirectory.isBlank()) {
            builder.workingDirectory(Path.of(workingDirectory));
        }
        if (trustPolicy != null && !trustPolicy.isBlank()) {
            try {
                builder.trustPolicy(TrustPolicy.valueOf(trustPolicy.trim()));
            } catch (IllegalArgumentException invalidPolicy) {
                throw new ConfigurationException(
                        io.cortexj.ErrorCode.CONF_INVALID,
                        "cortexj.trust-policy '" + trustPolicy + "' is not a TrustPolicy: "
                                + Arrays.toString(TrustPolicy.values()),
                        "Use one of the enum names exactly as listed, e.g. ALLOW_UNSIGNED_WITH_WARNING.");
            }
        }
        if (memoryLimit != null) {
            builder.memoryLimitBytes(memoryLimit);
        }
        if (maxArtifactBytes != null) {
            builder.maxArtifactBytes(maxArtifactBytes);
        }
        if (shutdownHook != null) {
            builder.shutdownHook(shutdownHook);
        }
        if (remote.baseUrl != null && !remote.baseUrl.isBlank()) {
            builder.remoteBaseUrl(remote.baseUrl.trim());
        }
        if (remote.apiKey != null && !remote.apiKey.isBlank()) {
            builder.remoteApiKey(remote.apiKey.trim());
        }
        if (remote.modelPrefix != null && !remote.modelPrefix.isBlank()) {
            builder.remoteModelPrefix(remote.modelPrefix.trim());
        }
        return builder;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Boolean getOffline() {
        return offline;
    }

    public void setOffline(Boolean offline) {
        this.offline = offline;
    }

    public Boolean getAllowRemoteRuntime() {
        return allowRemoteRuntime;
    }

    public void setAllowRemoteRuntime(Boolean allowRemoteRuntime) {
        this.allowRemoteRuntime = allowRemoteRuntime;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public void setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    public String getTrustPolicy() {
        return trustPolicy;
    }

    public void setTrustPolicy(String trustPolicy) {
        this.trustPolicy = trustPolicy;
    }

    public Long getMemoryLimit() {
        return memoryLimit;
    }

    public void setMemoryLimit(Long memoryLimit) {
        this.memoryLimit = memoryLimit;
    }

    public Long getMaxArtifactBytes() {
        return maxArtifactBytes;
    }

    public void setMaxArtifactBytes(Long maxArtifactBytes) {
        this.maxArtifactBytes = maxArtifactBytes;
    }

    public Boolean getShutdownHook() {
        return shutdownHook;
    }

    public void setShutdownHook(Boolean shutdownHook) {
        this.shutdownHook = shutdownHook;
    }

    public Remote getRemote() {
        return remote;
    }

    /** Remote-runtime endpoint configuration ({@code cortexj.remote.*}). */
    public static final class Remote {

        private String baseUrl;
        private String apiKey;
        private String modelPrefix;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModelPrefix() {
            return modelPrefix;
        }

        public void setModelPrefix(String modelPrefix) {
            this.modelPrefix = modelPrefix;
        }
    }
}
