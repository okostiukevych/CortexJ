package io.cortexj.hardware;

import io.cortexj.hardware.internal.EnvironmentProbe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentProbeTest {

    @Test
    void probeReportsJvmAndOs() {
        PlatformProbes probes = new PlatformProbes();
        EnvironmentProfile profile = probes.environment();
        assertTrue(profile.jvmFeatureVersion() >= 8);
        assertTrue(profile.logicalCores() >= 1);
        assertTrue(profile.maxHeapBytes() > 0);
        assertTrue(profile.osArchKey().contains("-"));
        assertTrue(profile.operatingSystem() != null && !profile.operatingSystem().isEmpty());
    }

    @Test
    void parsesLegacyAndModernVersionStrings() {
        assertTrue(EnvironmentProbe.parseFeatureVersion("1.8.0_451", "1.8") == 8);
        assertTrue(EnvironmentProbe.parseFeatureVersion("25.0.4", "25") == 25);
        assertTrue(EnvironmentProbe.parseFeatureVersion("11.0.27", "11") == 11);
    }

    @Test
    void hardwareProbeDetectsCpuAndMemory() {
        PlatformProbes probes = new PlatformProbes();
        EnvironmentProfile environment = probes.environment();
        HardwareProfile hardware = probes.hardware(environment);
        assertTrue(!hardware.devicesOfType(io.cortexj.runtime.DeviceType.CPU).isEmpty());
        assertTrue(hardware.detectionNotes().size() >= 0);
    }
}
