package io.cortexj.hardware;

import io.cortexj.hardware.internal.EnvironmentProbe;
import io.cortexj.hardware.internal.HardwareProbe;

public final class PlatformProbes {

    public EnvironmentProfile environment() {
        return new EnvironmentProbe().probe();
    }

    public HardwareProfile hardware(EnvironmentProfile environment) {
        return new HardwareProbe().probe(environment);
    }
}
