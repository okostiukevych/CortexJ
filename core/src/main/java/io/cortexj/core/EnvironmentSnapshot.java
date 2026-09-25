package io.cortexj.core;

public final class EnvironmentSnapshot {

    public final io.cortexj.hardware.EnvironmentProfile environment;
    public final io.cortexj.hardware.HardwareProfile hardware;

    public EnvironmentSnapshot(io.cortexj.hardware.EnvironmentProfile environment,
                              io.cortexj.hardware.HardwareProfile hardware) {
        this.environment = environment;
        this.hardware = hardware;
    }

    public static EnvironmentSnapshot probe() {
        io.cortexj.hardware.probe.PlatformProbes probes = new io.cortexj.hardware.probe.PlatformProbes();
        io.cortexj.hardware.EnvironmentProfile environment = probes.environment();
        return new EnvironmentSnapshot(environment, probes.hardware(environment));
    }
}
