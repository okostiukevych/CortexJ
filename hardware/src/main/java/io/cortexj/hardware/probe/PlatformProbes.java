package io.cortexj.hardware.probe;

import io.cortexj.hardware.EnvironmentProfile;
import io.cortexj.hardware.HardwareProfile;
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
