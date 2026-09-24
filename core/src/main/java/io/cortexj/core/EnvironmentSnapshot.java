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
        io.cortexj.hardware.PlatformProbes probes = new io.cortexj.hardware.PlatformProbes();
        io.cortexj.hardware.EnvironmentProfile environment = probes.environment();
        return new EnvironmentSnapshot(environment, probes.hardware(environment));
    }
}
