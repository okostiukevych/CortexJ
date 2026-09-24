package io.cortexj.spi.runtime;

import io.cortexj.runtime.RuntimeDescriptor;
import io.cortexj.runtime.RuntimeId;

public interface RuntimeProvider {

    RuntimeId id();

    RuntimeDescriptor descriptor();

    RuntimeProbeResult probe(RuntimeProbeContext context);

    RuntimeSupport evaluate(RuntimeEvaluationRequest request);

    ModelRuntime open(RuntimeOpenRequest request);
}
