package io.cortexj.lab;

/**
 * Live environment description for reproducible lab work: what JVM, OS
 * and platform version this session runs on, captured at call time.
 */
public final class EnvironmentDescriptor {

    private final String javaVersion;
    private final String jvmVendor;
    private final String osArch;
    private final int availableCores;
    private final String cortexjVersion;
    private final long timestampMillis;

    public EnvironmentDescriptor(String javaVersion, String jvmVendor, String osArch,
                                 int availableCores, String cortexjVersion, long timestampMillis) {
        this.javaVersion = javaVersion == null ? "" : javaVersion;
        this.jvmVendor = jvmVendor == null ? "" : jvmVendor;
        this.osArch = osArch == null ? "" : osArch;
        this.availableCores = availableCores;
        this.cortexjVersion = cortexjVersion == null ? "unknown" : cortexjVersion;
        this.timestampMillis = timestampMillis;
    }

    public String javaVersion() {
        return javaVersion;
    }

    public String jvmVendor() {
        return jvmVendor;
    }

    public String osArch() {
        return osArch;
    }

    public int availableCores() {
        return availableCores;
    }

    public String cortexjVersion() {
        return cortexjVersion;
    }

    public long timestampMillis() {
        return timestampMillis;
    }

    @Override
    public String toString() {
        return "Java " + javaVersion + " (" + jvmVendor + "), " + osArch
                + ", " + availableCores + " cores, cortexj " + cortexjVersion;
    }
}
