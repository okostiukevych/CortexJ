package io.cortexj.core;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class Configuration {

    public final boolean offline;
    public final Path workingDirectory;
    public final Long memoryLimitBytes;
    public final long maxArtifactBytes;
    public final String remoteBaseUrl;
    public final String remoteApiKey;
    public final boolean remoteRuntimeAllowed;
    public final io.cortexj.model.TrustPolicy trustPolicy;
    public final String proxyHost;
    public final String proxyPort;

    private static final String SYSPROP_PREFIX = "cortexj.";

    public Configuration(boolean offline,
                        Path workingDirectory,
                        Long memoryLimitBytes,
                        long maxArtifactBytes,
                        String remoteBaseUrl,
                        String remoteApiKey,
                        boolean remoteRuntimeAllowed,
                        io.cortexj.model.TrustPolicy trustPolicy,
                        String proxyHost,
                        String proxyPort) {
        this.offline = offline;
        this.workingDirectory = workingDirectory;
        this.memoryLimitBytes = memoryLimitBytes;
        this.maxArtifactBytes = maxArtifactBytes;
        this.remoteBaseUrl = remoteBaseUrl;
        this.remoteApiKey = remoteApiKey;
        this.remoteRuntimeAllowed = remoteRuntimeAllowed;
        this.trustPolicy = trustPolicy == null
                ? io.cortexj.model.TrustPolicy.ALLOW_UNSIGNED_WITH_WARNING : trustPolicy;
        this.proxyHost = emptyToNull(proxyHost);
        this.proxyPort = emptyToNull(proxyPort);
    }

    private static String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    public static Configuration from(io.cortexj.CortexJBuilder builder, EnvironmentSnapshot snapshot) {
        boolean offline = builder.isOffline() || syspropFlag("offline", envFlag("CORTEXJ_OFFLINE"));

        Path workingDirectory = builder.workingDirectory();
        if (workingDirectory == null) {
            String explicit = System.getProperty(SYSPROP_PREFIX + "home");
            if (explicit == null || explicit.isEmpty()) {
                explicit = System.getenv("CORTEXJ_HOME");
            }
            if (explicit != null && !explicit.isEmpty()) {
                workingDirectory = Paths.get(explicit);
            } else {
                String userHome = System.getProperty("user.home", ".");
                workingDirectory = Paths.get(userHome, ".cortexj");
            }
        }

        Long memoryLimit = builder.memoryLimitBytes();
        if (memoryLimit == null) {
            // audit 2026-10-07 (#148): env parity with maxArtifactBytes
            // (CORTEXJ_MAX_ARTIFACT_BYTES) - operators get the same knob in
            // containers without sysprops
            String raw = firstNonEmpty(System.getProperty(SYSPROP_PREFIX + "memory.limit.bytes"),
                    env("CORTEXJ_MEMORY_LIMIT_BYTES"));
            if (raw != null) {
                try {
                    memoryLimit = Long.valueOf(raw.trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }

        long maxArtifactBytes = 0L;
        Long configuredMaxArtifact = builder.maxArtifactBytes();
        if (configuredMaxArtifact == null) {
            String raw = firstNonEmpty(System.getProperty(SYSPROP_PREFIX + "download.max.bytes"),
                    env("CORTEXJ_MAX_ARTIFACT_BYTES"));
            if (raw != null) {
                try {
                    configuredMaxArtifact = Long.valueOf(raw);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (configuredMaxArtifact != null && configuredMaxArtifact.longValue() > 0L) {
            maxArtifactBytes = configuredMaxArtifact.longValue();
        }

        String remoteBaseUrl = firstNonEmpty(builder.remoteBaseUrl(),
                System.getProperty(SYSPROP_PREFIX + "remote.base.url"),
                env("CORTEXJ_REMOTE_BASE_URL"),
                env("OPENAI_BASE_URL"));

        String remoteApiKey = firstNonEmpty(builder.remoteApiKey(),
                System.getProperty(SYSPROP_PREFIX + "remote.api.key"),
                env("CORTEXJ_REMOTE_API_KEY"),
                env("OPENAI_API_KEY"));
        // audit #125: system properties are exposed by jcmd VM.system_properties
        // and hs_err dumps - credentials there deserve a loud warning
        if (builder.remoteApiKey() == null
                && System.getProperty(SYSPROP_PREFIX + "remote.api.key") != null
                && !System.getProperty(SYSPROP_PREFIX + "remote.api.key").trim().isEmpty()) {
            java.util.logging.Logger.getLogger(Configuration.class.getName()).warning(
                    "cortexj.remote.api.key is set via a system property; system properties "
                            + "are readable by 'jcmd VM.system_properties' and hs_err dumps - "
                            + "prefer the CORTEXJ_REMOTE_API_KEY environment variable");
        }

        String proxyHost = firstNonEmpty(builder.proxyHost(),
                System.getProperty("cortexj.proxy.host"),
                env("CORTEXJ_PROXY_HOST"));
        String proxyPort = firstNonEmpty(builder.proxyPort(),
                System.getProperty("cortexj.proxy.port"),
                env("CORTEXJ_PROXY_PORT"));
        return new Configuration(offline, workingDirectory, memoryLimit, maxArtifactBytes, remoteBaseUrl, remoteApiKey,
                builder.isRemoteRuntimeAllowed(), builder.trustPolicy(), proxyHost, proxyPort);
    }

    public java.util.Map<String, String> runtimeConfiguration() {
        java.util.Map<String, String> config = new java.util.LinkedHashMap<String, String>();
        config.put("offline", String.valueOf(offline));
        if (remoteBaseUrl != null) {
            config.put("remote.baseUrl", remoteBaseUrl);
        }
        // audit 2026-10-07 (#98): the raw API key is NEVER broadcast in this
        // map - it used to reach every RuntimeProvider.open(), including
        // third-party SPI implementations. The built-in openai-remote runtime
        // receives it through RuntimeOpenRequest.remoteApiKey(); everyone else
        // must source credentials from their own options/environment.
        if (memoryLimitBytes != null) {
            config.put("memoryLimitBytes", String.valueOf(memoryLimitBytes));
        }
        if (maxArtifactBytes > 0L) {
            config.put("maxArtifactBytes", String.valueOf(maxArtifactBytes));
        }
        if (proxyHost != null && proxyPort != null) {
            config.put("proxy.host", proxyHost);
            config.put("proxy.port", proxyPort);
        }
        return config;
    }

    private static boolean syspropFlag(String key, boolean fallback) {
        String value = System.getProperty(SYSPROP_PREFIX + key);
        return value == null ? fallback : Boolean.parseBoolean(value.trim());
    }

    private static boolean envFlag(String key) {
        String value = System.getenv(key);
        return value != null && Boolean.parseBoolean(value.trim());
    }

    private static String env(String key) {
        String value = System.getenv(key);
        return value == null || value.isEmpty() ? null : value;
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }
}
