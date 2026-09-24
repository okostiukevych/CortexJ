package io.cortexj.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DeviceDescriptor {

    private final DeviceType type;
    private final String name;
    private final long memoryBytes;
    private final Map<String, String> metadata;

    private DeviceDescriptor(DeviceType type, String name, long memoryBytes, Map<String, String> metadata) {
        this.type = type;
        this.name = name;
        this.memoryBytes = memoryBytes;
        this.metadata = metadata == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(metadata));
    }

    public static DeviceDescriptor of(DeviceType type, String name, long memoryBytes) {
        return new DeviceDescriptor(type, name, memoryBytes, null);
    }

    public static DeviceDescriptor of(DeviceType type, String name, long memoryBytes, Map<String, String> metadata) {
        return new DeviceDescriptor(type, name, memoryBytes, metadata);
    }

    public DeviceType type() {
        return type;
    }

    public String name() {
        return name;
    }

    public long memoryBytes() {
        return memoryBytes;
    }

    public Map<String, String> metadata() {
        return metadata;
    }

    @Override
    public String toString() {
        return type + (name == null ? "" : " (" + name + ")");
    }
}
