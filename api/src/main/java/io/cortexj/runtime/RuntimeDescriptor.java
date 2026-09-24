package io.cortexj.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.TaskCapability;

public final class RuntimeDescriptor {

    public enum Maturity { EXPERIMENTAL, BETA, STABLE }

    private final RuntimeId id;
    private final String version;
    private final Set<ArtifactFormat> formats;
    private final Set<TaskCapability> tasks;
    private final Set<DeviceType> devices;
    private final int minJavaFeatureVersion;
    private final boolean streamingSupported;
    private final boolean structuredOutputNative;
    private final boolean nativeRuntime;
    private final ThreadSafety threadSafety;
    private final Maturity maturity;
    private final Map<String, String> metadata;

    private RuntimeDescriptor(Builder builder) {
        this.id = builder.id;
        this.version = builder.version;
        this.formats = Collections.unmodifiableSet(new LinkedHashSet<ArtifactFormat>(builder.formats));
        this.tasks = Collections.unmodifiableSet(new LinkedHashSet<TaskCapability>(builder.tasks));
        this.devices = Collections.unmodifiableSet(new LinkedHashSet<DeviceType>(builder.devices));
        this.minJavaFeatureVersion = builder.minJavaFeatureVersion;
        this.streamingSupported = builder.streamingSupported;
        this.structuredOutputNative = builder.structuredOutputNative;
        this.nativeRuntime = builder.nativeRuntime;
        this.threadSafety = builder.threadSafety;
        this.maturity = builder.maturity;
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<String, String>(builder.metadata));
    }

    public RuntimeId id() {
        return id;
    }

    public String version() {
        return version;
    }

    public Set<ArtifactFormat> formats() {
        return formats;
    }

    public Set<TaskCapability> tasks() {
        return tasks;
    }

    public Set<DeviceType> devices() {
        return devices;
    }

    public int minJavaFeatureVersion() {
        return minJavaFeatureVersion;
    }

    public boolean isStreamingSupported() {
        return streamingSupported;
    }

    public boolean isStructuredOutputNative() {
        return structuredOutputNative;
    }

    public boolean isNativeRuntime() {
        return nativeRuntime;
    }

    public ThreadSafety threadSafety() {
        return threadSafety;
    }

    public Maturity maturity() {
        return maturity;
    }

    public Map<String, String> metadata() {
        return metadata;
    }

    public boolean supportsFormat(ArtifactFormat format) {
        return formats.contains(format);
    }

    public boolean supportsTask(TaskCapability task) {
        return tasks.contains(task);
    }

    public boolean supportsDevice(DeviceType device) {
        return devices.contains(device);
    }

    public static Builder builder(RuntimeId id) {
        return new Builder(id);
    }

    public static final class Builder {

        private final RuntimeId id;
        private String version = "0.0.0";
        private final Set<ArtifactFormat> formats = new LinkedHashSet<ArtifactFormat>();
        private final Set<TaskCapability> tasks = new LinkedHashSet<TaskCapability>();
        private final Set<DeviceType> devices = new LinkedHashSet<DeviceType>();
        private int minJavaFeatureVersion = 8;
        private boolean streamingSupported;
        private boolean structuredOutputNative;
        private boolean nativeRuntime;
        private ThreadSafety threadSafety = ThreadSafety.SESSION_SCOPED;
        private Maturity maturity = Maturity.EXPERIMENTAL;
        private final Map<String, String> metadata = new LinkedHashMap<String, String>();

        private Builder(RuntimeId id) {
            if (id == null) {
                throw new IllegalArgumentException("Runtime id must not be null");
            }
            this.id = id;
        }

        public Builder version(String value) {
            this.version = value;
            return this;
        }

        public Builder format(ArtifactFormat format) {
            formats.add(format);
            return this;
        }

        public Builder task(TaskCapability task) {
            tasks.add(task);
            return this;
        }

        public Builder device(DeviceType device) {
            devices.add(device);
            return this;
        }

        public Builder minJavaFeatureVersion(int version) {
            this.minJavaFeatureVersion = version;
            return this;
        }

        public Builder streamingSupported(boolean supported) {
            this.streamingSupported = supported;
            return this;
        }

        public Builder structuredOutputNative(boolean supported) {
            this.structuredOutputNative = supported;
            return this;
        }

        public Builder nativeRuntime(boolean nativeRuntime) {
            this.nativeRuntime = nativeRuntime;
            return this;
        }

        public Builder threadSafety(ThreadSafety threadSafety) {
            this.threadSafety = threadSafety == null ? ThreadSafety.SESSION_SCOPED : threadSafety;
            return this;
        }

        public Builder maturity(Maturity maturity) {
            this.maturity = maturity == null ? Maturity.EXPERIMENTAL : maturity;
            return this;
        }

        public Builder metadata(String key, String value) {
            metadata.put(key, value);
            return this;
        }

        public RuntimeDescriptor build() {
            return new RuntimeDescriptor(this);
        }
    }
}
