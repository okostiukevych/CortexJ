package io.cortexj.core;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class Configuration {

    public final boolean offline;
    public final Path workingDirectory;
    public final Long memoryLimitBytes;
    public final String remoteBaseUrl;
    public final String remoteApiKey;
    public final boolean remoteRuntimeAllowed;

    private static final String SYSPROP_PREFIX = "cortexj.";

    public Configuration(boolean offline,
                        Path workingDirectory,
                        Long memoryLimitBytes,
                        String remoteBaseUrl,
                        String remoteApiKey,
                        boolean remoteRuntimeAllowed) {
        this.offline = offline;
        this.workingDirectory = workingDirectory;
        this.memoryLimitBytes = memoryLimitBytes;
        this.remoteBaseUrl = remoteBaseUrl;
        this.remoteApiKey = remoteApiKey;
        this.remoteRuntimeAllowed = remoteRuntimeAllowed;
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
            String raw = System.getProperty(SYSPROP_PREFIX + "memory.limit.bytes");
            if (raw != null && !raw.isEmpty()) {
                try {
                    memoryLimit = Long.valueOf(raw.trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }

        String remoteBaseUrl = firstNonEmpty(builder.remoteBaseUrl(),
                System.getProperty(SYSPROP_PREFIX + "remote.base.url"),
                env("CORTEXJ_REMOTE_BASE_URL"),
                env("OPENAI_BASE_URL"));

        String remoteApiKey = firstNonEmpty(builder.remoteApiKey(),
                System.getProperty(SYSPROP_PREFIX + "remote.api.key"),
                env("CORTEXJ_REMOTE_API_KEY"),
                env("OPENAI_API_KEY"));

        return new Configuration(offline, workingDirectory, memoryLimit, remoteBaseUrl, remoteApiKey,
                builder.isRemoteRuntimeAllowed());
    }

    public java.util.Map<String, String> runtimeConfiguration() {
        java.util.Map<String, String> config = new java.util.LinkedHashMap<String, String>();
        config.put("offline", String.valueOf(offline));
        if (remoteBaseUrl != null) {
            config.put("remote.baseUrl", remoteBaseUrl);
        }
        if (remoteApiKey != null) {
            config.put("remote.apiKeyPresent", "true");
        }
        if (memoryLimitBytes != null) {
            config.put("memoryLimitBytes", String.valueOf(memoryLimitBytes));
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
