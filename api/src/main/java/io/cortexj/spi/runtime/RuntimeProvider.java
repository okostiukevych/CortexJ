package io.cortexj.spi.runtime;

import io.cortexj.hardware.EnvironmentProfile;
import io.cortexj.hardware.HardwareProfile;
import io.cortexj.runtime.RuntimeId;

public interface RuntimeProvider {

    RuntimeId id();

    RuntimeProbeResult probe(RuntimeProbeContext context);

    RuntimeSupport evaluate(RuntimeEvaluationRequest request);

    ModelRuntime open(RuntimeOpenRequest request);
}
