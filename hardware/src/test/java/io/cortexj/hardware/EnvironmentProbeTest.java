package io.cortexj.hardware;

import io.cortexj.hardware.probe.PlatformProbes;

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

    @Test
    void hardwareProbeHandlesSyntheticEnvironments() {
        PlatformProbes probes = new PlatformProbes();
        EnvironmentProfile windowsEnv = new EnvironmentProfile(
                "Vendor", "VM", 17, "17.0.1", "Windows 11", "amd64",
                4, 512L * 1024L * 1024L, 0L, 0L, false, null);
        HardwareProfile winHw = probes.hardware(windowsEnv);
        assertTrue(winHw.isDirectmlAvailable());
        assertTrue(!winHw.devicesOfType(io.cortexj.runtime.DeviceType.CPU).isEmpty());

        EnvironmentProfile linuxEnv = new EnvironmentProfile(
                "Vendor", "VM", 21, "21.0.2", "Linux", "aarch64",
                8, 1024L * 1024L * 1024L, 4096L * 1024L * 1024L, 2048L * 1024L * 1024L, true,
                java.util.Collections.singletonMap("container", "cgroupv2"));
        HardwareProfile linuxHw = probes.hardware(linuxEnv);
        org.junit.jupiter.api.Assertions.assertFalse(linuxHw.isDirectmlAvailable());
        org.junit.jupiter.api.Assertions.assertFalse(linuxHw.isMetalAvailable());
        assertTrue(EnvironmentProbe.parseFeatureVersion(null, null) == 8);
        assertTrue(EnvironmentProbe.parseFeatureVersion("21-ea+35", "21") == 21);
    }
}
