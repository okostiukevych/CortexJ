package io.cortexj.hardware;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class EnvironmentProfile {

    private final String jvmVendor;
    private final String jvmName;
    private final int jvmFeatureVersion;
    private final String javaVersion;
    private final String operatingSystem;
    private final String architecture;
    private final int logicalCores;
    private final long maxHeapBytes;
    private final long totalPhysicalMemoryBytes;
    private final long availableMemoryEstimateBytes;
    private final boolean nativeAccessLikely;
    private final Map<String, String> metadata;

    public EnvironmentProfile(String jvmVendor,
                              String jvmName,
                              int jvmFeatureVersion,
                              String javaVersion,
                              String operatingSystem,
                              String architecture,
                              int logicalCores,
                              long maxHeapBytes,
                              long totalPhysicalMemoryBytes,
                              long availableMemoryEstimateBytes,
                              boolean nativeAccessLikely,
                              Map<String, String> metadata) {
        this.jvmVendor = jvmVendor;
        this.jvmName = jvmName;
        this.jvmFeatureVersion = jvmFeatureVersion;
        this.javaVersion = javaVersion;
        this.operatingSystem = operatingSystem;
        this.architecture = architecture;
        this.logicalCores = logicalCores;
        this.maxHeapBytes = maxHeapBytes;
        this.totalPhysicalMemoryBytes = totalPhysicalMemoryBytes;
        this.availableMemoryEstimateBytes = availableMemoryEstimateBytes;
        this.nativeAccessLikely = nativeAccessLikely;
        this.metadata = metadata == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(metadata));
    }

    public String jvmVendor() {
        return jvmVendor;
    }

    public String jvmName() {
        return jvmName;
    }

    public int jvmFeatureVersion() {
        return jvmFeatureVersion;
    }

    public String javaVersion() {
        return javaVersion;
    }

    public String operatingSystem() {
        return operatingSystem;
    }

    public String architecture() {
        return architecture;
    }

    public int logicalCores() {
        return logicalCores;
    }

    public long maxHeapBytes() {
        return maxHeapBytes;
    }

    public long totalPhysicalMemoryBytes() {
        return totalPhysicalMemoryBytes;
    }

    public long availableMemoryEstimateBytes() {
        return availableMemoryEstimateBytes;
    }

    public boolean isNativeAccessLikely() {
        return nativeAccessLikely;
    }

    public Map<String, String> metadata() {
        return metadata;
    }

    public String osArchKey() {
        String os = operatingSystem == null ? "unknown" : operatingSystem.toLowerCase();
        String arch = architecture == null ? "unknown" : architecture.toLowerCase();
        if (os.contains("mac") || os.contains("darwin")) {
            os = "macos";
        }
        if (arch.contains("x86_64") || arch.contains("amd64")) {
            arch = "x86_64";
        } else if (arch.contains("aarch64") || arch.contains("arm64")) {
            arch = "aarch64";
        }
        return os + "-" + arch;
    }

    @Override
    public String toString() {
        return "Java " + jvmFeatureVersion + " (" + jvmVendor + "), " + osArchKey()
                + ", " + logicalCores + " cores"
                + (totalPhysicalMemoryBytes > 0 ? ", ~" + (totalPhysicalMemoryBytes / (1024L * 1024L * 1024L)) + " GiB RAM" : "");
    }
}
