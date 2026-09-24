package io.cortexj.planner;

import io.cortexj.model.ModelReference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.cortexj.runtime.DeviceType;
import io.cortexj.model.ArtifactFormat;

public final class ExecutionRequest {

    private final ModelReference model;
    private final String requiredRuntime;
    private final String preferredRuntime;
    private final Set<String> forbiddenRuntimes;
    private final Set<String> allowedRepositories;
    private final DevicePreference requiredDevice;
    private final DevicePreference preferredDevice;
    private final Long memoryLimitBytes;
    private final FallbackPolicy fallbackPolicy;
    private final QualityProfile qualityProfile;
    private final boolean offline;
    private final Set<String> allowedLicenses;
    private final ArtifactFormat requiredFormat;
    private final String preferredQuantization;
    private final ReproducibilityMode reproducibilityMode;
    private final ModelLockfile lockfile;
    private final Map<String, Map<String, Object>> runtimeOptions;
    private final boolean downloadAllowed;

    private ExecutionRequest(Builder builder) {
        this.model = builder.model;
        this.requiredRuntime = normalize(builder.requiredRuntime);
        this.preferredRuntime = normalize(builder.preferredRuntime);
        this.forbiddenRuntimes = Collections.unmodifiableSet(new LinkedHashSet<String>(normalizeAll(builder.forbiddenRuntimes)));
        this.allowedRepositories = Collections.unmodifiableSet(new LinkedHashSet<String>(normalizeAll(builder.allowedRepositories)));
        this.requiredDevice = builder.requiredDevice;
        this.preferredDevice = builder.preferredDevice;
        this.memoryLimitBytes = builder.memoryLimitBytes;
        this.fallbackPolicy = builder.fallbackPolicy == null ? FallbackPolicy.LOAD_TIME_ONLY : builder.fallbackPolicy;
        this.qualityProfile = builder.qualityProfile == null ? QualityProfile.BALANCED : builder.qualityProfile;
        this.offline = builder.offline;
        this.allowedLicenses = Collections.unmodifiableSet(new LinkedHashSet<String>(normalizeAll(builder.allowedLicenses)));
        this.requiredFormat = builder.requiredFormat;
        this.preferredQuantization = normalize(builder.preferredQuantization);
        this.reproducibilityMode = builder.reproducibilityMode == null ? ReproducibilityMode.FLEXIBLE : builder.reproducibilityMode;
        this.lockfile = builder.lockfile;
        this.runtimeOptions = deepCopy(builder.runtimeOptions);
        this.downloadAllowed = builder.downloadAllowed;
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase();
    }

    private static List<String> normalizeAll(Iterable<String> values) {
        List<String> result = new ArrayList<String>();
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                result.add(value.trim().toLowerCase());
            }
        }
        return result;
    }

    private static Map<String, Map<String, Object>> deepCopy(Map<String, Map<String, Object>> source) {
        Map<String, Map<String, Object>> copy = new LinkedHashMap<String, Map<String, Object>>();
        for (Map.Entry<String, Map<String, Object>> entry : source.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<String, Object>(entry.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    public static Builder builder(ModelReference model) {
        return new Builder(model);
    }

    public static ExecutionRequest forModel(ModelReference model) {
        return builder(model).build();
    }

    public ModelReference model() {
        return model;
    }

    public String requiredRuntime() {
        return requiredRuntime;
    }

    public String preferredRuntime() {
        return preferredRuntime;
    }

    public Set<String> forbiddenRuntimes() {
        return forbiddenRuntimes;
    }

    public Set<String> allowedRepositories() {
        return allowedRepositories;
    }

    public boolean isRepositoryAllowed(String repositoryId) {
        return allowedRepositories.isEmpty()
                || allowedRepositories.contains(repositoryId == null ? "" : repositoryId.trim().toLowerCase());
    }

    public DevicePreference requiredDevice() {
        return requiredDevice;
    }

    public DevicePreference preferredDevice() {
        return preferredDevice;
    }

    public Long memoryLimitBytes() {
        return memoryLimitBytes;
    }

    public FallbackPolicy fallbackPolicy() {
        return fallbackPolicy;
    }

    public QualityProfile qualityProfile() {
        return qualityProfile;
    }

    public boolean isOffline() {
        return offline;
    }

    public Set<String> allowedLicenses() {
        return allowedLicenses;
    }

    public ArtifactFormat requiredFormat() {
        return requiredFormat;
    }

    public String preferredQuantization() {
        return preferredQuantization;
    }

    public ReproducibilityMode reproducibilityMode() {
        return reproducibilityMode;
    }

    public ModelLockfile lockfile() {
        return lockfile;
    }

    public Map<String, Map<String, Object>> runtimeOptions() {
        return runtimeOptions;
    }

    public Map<String, Object> optionsFor(String runtimeId) {
        Map<String, Object> options = runtimeOptions.get(runtimeId);
        return options == null ? Collections.<String, Object>emptyMap() : options;
    }

    public boolean isDownloadAllowed() {
        return downloadAllowed;
    }

    public boolean isFallbackEnabled() {
        return fallbackPolicy != FallbackPolicy.DISABLED && requiredRuntime == null;
    }

    public static final class Builder {

        private final ModelReference model;
        private String requiredRuntime;
        private String preferredRuntime;
        private final List<String> forbiddenRuntimes = new ArrayList<String>();
        private final List<String> allowedRepositories = new ArrayList<String>();
        private DevicePreference requiredDevice;
        private DevicePreference preferredDevice;
        private Long memoryLimitBytes;
        private FallbackPolicy fallbackPolicy;
        private QualityProfile qualityProfile;
        private boolean offline;
        private final List<String> allowedLicenses = new ArrayList<String>();
        private ArtifactFormat requiredFormat;
        private String preferredQuantization;
        private ReproducibilityMode reproducibilityMode;
        private ModelLockfile lockfile;
        private final Map<String, Map<String, Object>> runtimeOptions = new LinkedHashMap<String, Map<String, Object>>();
        private boolean downloadAllowed = true;

        private Builder(ModelReference model) {
            if (model == null) {
                throw new IllegalArgumentException("Model reference must not be null");
            }
            this.model = model;
        }

        public Builder requiredRuntime(String runtimeId) {
            this.requiredRuntime = runtimeId;
            return this;
        }

        public Builder preferredRuntime(String runtimeId) {
            this.preferredRuntime = runtimeId;
            return this;
        }

        public Builder forbiddenRuntime(String runtimeId) {
            this.forbiddenRuntimes.add(runtimeId);
            return this;
        }

        public Builder allowedRepository(String repositoryId) {
            this.allowedRepositories.add(repositoryId);
            return this;
        }

        public Builder requiredDevice(DevicePreference device) {
            this.requiredDevice = device;
            return this;
        }

        public Builder preferredDevice(DevicePreference device) {
            this.preferredDevice = device;
            return this;
        }

        public Builder memoryLimitBytes(long bytes) {
            this.memoryLimitBytes = Long.valueOf(bytes);
            return this;
        }

        public Builder fallbackPolicy(FallbackPolicy policy) {
            this.fallbackPolicy = policy;
            return this;
        }

        public Builder qualityProfile(QualityProfile profile) {
            this.qualityProfile = profile;
            return this;
        }

        public Builder offline(boolean offline) {
            this.offline = offline;
            return this;
        }

        public Builder allowLicense(String license) {
            this.allowedLicenses.add(license);
            return this;
        }

        public Builder requiredFormat(ArtifactFormat format) {
            this.requiredFormat = format;
            return this;
        }

        public Builder preferredQuantization(String quantization) {
            this.preferredQuantization = quantization;
            return this;
        }

        public Builder reproducibilityMode(ReproducibilityMode mode) {
            this.reproducibilityMode = mode;
            return this;
        }

        public Builder lockfile(ModelLockfile lockfile) {
            this.lockfile = lockfile;
            if (lockfile != null) {
                if (reproducibilityMode == null || reproducibilityMode == ReproducibilityMode.FLEXIBLE) {
                    this.reproducibilityMode = ReproducibilityMode.PINNED_MODEL;
                }
            }
            return this;
        }

        public Builder runtimeOptions(RuntimeOptions options) {
            if (options != null) {
                runtimeOptions.putAll(options.asMap());
            }
            return this;
        }

        public Builder downloadsAllowed(boolean allowed) {
            this.downloadAllowed = allowed;
            return this;
        }

        public ExecutionRequest build() {
            return new ExecutionRequest(this);
        }
    }
}
