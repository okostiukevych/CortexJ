package io.cortexj.spi.support;

import io.cortexj.runtime.RuntimeDescriptor;
import io.cortexj.runtime.RuntimeId;
import io.cortexj.spi.runtime.ModelRuntime;
import io.cortexj.spi.runtime.RuntimeEvaluationRequest;
import io.cortexj.spi.runtime.RuntimeOpenRequest;
import io.cortexj.spi.runtime.RuntimeProbeContext;
import io.cortexj.spi.runtime.RuntimeProbeResult;
import io.cortexj.spi.runtime.RuntimeProvider;
import io.cortexj.spi.runtime.RuntimeSupport;

public abstract class RuntimeProviderSupport implements RuntimeProvider {

    private final RuntimeId runtimeId;
    private final RuntimeDescriptor descriptor;

    protected RuntimeProviderSupport(RuntimeDescriptor descriptor) {
        this.runtimeId = descriptor.id();
        this.descriptor = descriptor;
    }

    @Override
    public final RuntimeId id() {
        return runtimeId;
    }

    public final RuntimeDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public RuntimeProbeResult probe(RuntimeProbeContext context) {
        return RuntimeProbeResult.available(descriptor.id() + " is available");
    }

    @Override
    public abstract RuntimeSupport evaluate(RuntimeEvaluationRequest request);

    @Override
    public abstract ModelRuntime open(RuntimeOpenRequest request);
}
