package io.cortexj.hardware.internal;

import io.cortexj.hardware.EnvironmentProfile;
import io.cortexj.hardware.HardwareProfile;
import io.cortexj.runtime.DeviceDescriptor;
import io.cortexj.runtime.DeviceType;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class HardwareProbe {

    public HardwareProfile probe(EnvironmentProfile environment) {
        List<DeviceDescriptor> devices = new ArrayList<DeviceDescriptor>();
        List<String> notes = new ArrayList<String>();

        devices.add(DeviceDescriptor.of(
                DeviceType.CPU,
                cpuName(environment),
                environment.totalPhysicalMemoryBytes() > 0 ? environment.totalPhysicalMemoryBytes() : environment.maxHeapBytes()));

        boolean cuda = detectCuda(notes);
        boolean rocm = detectRocm(notes);
        boolean metal = detectMetal(environment, notes);
        boolean directml = detectDirectml(environment, notes);

        if (metal) {
            devices.add(DeviceDescriptor.of(DeviceType.GPU, "Apple GPU (Metal)", unifiedMemoryBytes(environment), null));
        } else if (cuda || rocm) {
            devices.add(DeviceDescriptor.of(DeviceType.GPU, cuda ? "CUDA GPU" : "ROCm GPU", 0L, null));
        }

        return new HardwareProfile(devices,
                environment.totalPhysicalMemoryBytes(),
                cuda, rocm, metal, directml, notes);
    }

    private static String cpuName(EnvironmentProfile environment) {
        String arch = environment.architecture();
        return environment.logicalCores() + "x " + arch + " CPU";
    }

    private static long unifiedMemoryBytes(EnvironmentProfile environment) {
        return environment.totalPhysicalMemoryBytes() > 0
                ? environment.totalPhysicalMemoryBytes()
                : environment.maxHeapBytes() * 4L;
    }

    private static boolean detectCuda(List<String> notes) {
        String env = System.getenv("CUDA_HOME");
        if (env != null && new File(env).isDirectory()) {
            notes.add("CUDA_HOME detected: " + env);
            return true;
        }
        return false;
    }

    private static boolean detectRocm(List<String> notes) {
        String env = System.getenv("ROCM_HOME");
        if (env != null && new File(env).isDirectory()) {
            notes.add("ROCM_HOME detected: " + env);
            return true;
        }
        return false;
    }

    private static boolean detectMetal(EnvironmentProfile environment, List<String> notes) {
        String os = environment.operatingSystem();
        if (os != null && (os.toLowerCase().contains("mac") || os.toLowerCase().contains("darwin"))) {
            File framework = new File("/System/Library/Frameworks/Metal.framework");
            notes.add("Metal assumed available on macOS");
            return framework.exists() || framework.isDirectory();
        }
        return false;
    }

    private static boolean detectDirectml(EnvironmentProfile environment, List<String> notes) {
        String os = environment.operatingSystem();
        return os != null && os.toLowerCase().contains("windows");
    }
}
