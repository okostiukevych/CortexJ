package io.cortexj.planner;

public enum ReasonCode {

    RUNTIME_UNAVAILABLE("runtime is not installed or not probeable"),
    RUNTIME_MISCONFIGURED("runtime is installed but misconfigured"),
    JVM_INCOMPATIBLE("current JVM feature version is below runtime requirements"),
    FORMAT_UNSUPPORTED("runtime does not support the artifact format"),
    CAPABILITY_UNSUPPORTED("runtime does not support the required model task"),
    DEVICE_UNAVAILABLE("required accelerator device is not present"),
    NATIVE_BINARY_MISSING("native binary for this OS/architecture is not available"),
    OS_ARCH_UNSUPPORTED("runtime does not support this OS/architecture"),
    MEMORY_LIMIT_EXCEEDED("estimated memory exceeds the configured hard limit"),
    MEMORY_INSUFFICIENT("estimated memory exceeds physically available memory"),
    LICENSE_BLOCKED("model license is blocked by the configured policy"),
    TRUST_POLICY_BLOCKED("artifact is blocked by the trust policy"),
    USER_REQUIREMENT_CONFLICT("candidate conflicts with an explicit user requirement"),
    FORBIDDEN_RUNTIME("runtime is explicitly forbidden by the user"),
    NO_CONVERTER("conversion is required but no registered converter exists"),
    OFFLINE_DOWNLOAD_FORBIDDEN("artifact requires a download while offline mode is active"),
    REMOTE_DISABLED("remote execution is disabled in the platform configuration"),
    MODEL_TASK_UNKNOWN("model does not declare the requested task"),
    REVISION_UNRESOLVED("model revision could not be resolved"),
    ARTIFACT_NOT_FOUND("no artifact is available for execution"),

    NATIVE_ARTIFACT_AVAILABLE("artifact is already available in a runtime-native format"),
    MEMORY_HEADROOM_OK("estimated memory fits the configured safety margin"),
    LOCAL_CACHE_HIT("artifact is already present in the local cache"),
    USER_PREFERENCE_MATCH("runtime matches the user preference"),
    DEVICE_PREFERENCE_MATCH("device matches the user preference"),
    RUNTIME_MATURITY_HIGH("runtime is marked stable"),
    QUALITY_PROFILE_MATCH("artifact quantization matches the quality profile"),
    LOWER_STARTUP_COST("lower estimated startup/conversion cost"),
    HIGHER_QUALITY_QUANTIZATION("higher quality quantization within the memory budget");

    private final String defaultText;

    ReasonCode(String defaultText) {
        this.defaultText = defaultText;
    }

    public String defaultText() {
        return defaultText;
    }
}
