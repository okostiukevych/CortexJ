package io.cortexj.hardware;

import io.cortexj.runtime.DeviceDescriptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class HardwareProfile {

    private final List<DeviceDescriptor> devices;
    private final long totalMemoryBytes;
    private final boolean cudaAvailable;
    private final boolean rocmAvailable;
    private final boolean metalAvailable;
    private final boolean directmlAvailable;
    private final List<String> detectionNotes;

    public HardwareProfile(List<DeviceDescriptor> devices,
                          long totalMemoryBytes,
                          boolean cudaAvailable,
                          boolean rocmAvailable,
                          boolean metalAvailable,
                          boolean directmlAvailable,
                          List<String> detectionNotes) {
        this.devices = Collections.unmodifiableList(new ArrayList<DeviceDescriptor>(devices));
        this.totalMemoryBytes = totalMemoryBytes;
        this.cudaAvailable = cudaAvailable;
        this.rocmAvailable = rocmAvailable;
        this.metalAvailable = metalAvailable;
        this.directmlAvailable = directmlAvailable;
        this.detectionNotes = Collections.unmodifiableList(new ArrayList<String>(detectionNotes));
    }

    public List<DeviceDescriptor> devices() {
        return devices;
    }

    public List<DeviceDescriptor> devicesOfType(io.cortexj.runtime.DeviceType type) {
        List<DeviceDescriptor> result = new ArrayList<DeviceDescriptor>();
        for (DeviceDescriptor device : devices) {
            if (device.type() == type) {
                result.add(device);
            }
        }
        return result;
    }

    public long totalMemoryBytes() {
        return totalMemoryBytes;
    }

    public boolean isCudaAvailable() {
        return cudaAvailable;
    }

    public boolean isRocmAvailable() {
        return rocmAvailable;
    }

    public boolean isMetalAvailable() {
        return metalAvailable;
    }

    public boolean isDirectmlAvailable() {
        return directmlAvailable;
    }

    public boolean hasAccelerator() {
        return cudaAvailable || rocmAvailable || metalAvailable || directmlAvailable || !devicesOfType(io.cortexj.runtime.DeviceType.GPU).isEmpty();
    }

    public List<String> detectionNotes() {
        return detectionNotes;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("CPU: ").append(devicesOfType(io.cortexj.runtime.DeviceType.CPU).size() >= 1 ? "present" : "unknown");
        for (DeviceDescriptor device : devicesOfType(io.cortexj.runtime.DeviceType.GPU)) {
            sb.append(", GPU: ").append(device.name());
        }
        if (totalMemoryBytes > 0) {
            sb.append(", RAM: ~").append(totalMemoryBytes / (1024L * 1024L * 1024L)).append(" GiB");
        }
        return sb.toString();
    }
}
