package io.cortexj.spi.support;

import io.cortexj.hardware.EnvironmentProfile;
import io.cortexj.hardware.HardwareProfile;
import io.cortexj.model.ModelArtifact;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.observability.PlatformEventListener;
import io.cortexj.planner.ExecutionRequest;
import io.cortexj.runtime.DeviceType;
import io.cortexj.runtime.RuntimeDescriptor;
import io.cortexj.runtime.RuntimeId;
import io.cortexj.spi.policy.PlanningContext;
import io.cortexj.spi.repository.ResolutionContext;
import io.cortexj.spi.repository.TokenSupplier;
import io.cortexj.spi.runtime.ModelRuntime;
import io.cortexj.spi.runtime.RuntimeEvaluationRequest;
import io.cortexj.spi.runtime.RuntimeModelRequest;
import io.cortexj.spi.runtime.RuntimeOpenRequest;
import io.cortexj.spi.runtime.RuntimeProbeContext;
import io.cortexj.spi.runtime.RuntimeProbeResult;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutorService;

public final class Contexts {

    private Contexts() {
    }

    public static RuntimeProbeContext probeContext(EnvironmentProfile environment, HardwareProfile hardware, boolean offline, Map<String, String> configuration) {
        return new RuntimeProbeContext(environment, hardware, offline, configuration);
    }

    public static RuntimeEvaluationRequest evaluationRequest(ModelDescriptor model, ModelArtifact artifact, EnvironmentProfile environment, HardwareProfile hardware, ExecutionRequest userRequest) {
        return new RuntimeEvaluationRequest(model, artifact, environment, hardware, userRequest);
    }

    public static RuntimeModelRequest modelRequest(ModelDescriptor model, ModelArtifact artifact, java.nio.file.Path artifactPath, DeviceType device, Map<String, Object> runtimeOptions) {
        return new RuntimeModelRequest(model, artifact, artifactPath, device, runtimeOptions);
    }

    public static RuntimeOpenRequest openRequest(ModelDescriptor model,
                                                ModelArtifact artifact,
                                                java.nio.file.Path artifactPath,
                                                DeviceType device,
                                                Map<String, Object> runtimeOptions,
                                                EnvironmentProfile environment,
                                                HardwareProfile hardware,
                                                ExecutorService executor,
                                                PlatformEventListener listener) {
        return new RuntimeOpenRequest(model, artifact, artifactPath, device, runtimeOptions, environment, hardware, executor, listener);
    }

    public static PlanningContext planningContext(EnvironmentProfile environment, HardwareProfile hardware, ExecutionRequest request, PlanningContext.CacheLookup cacheLookup) {
        return new PlanningContext(environment, hardware, request, cacheLookup);
    }

    public static ResolutionContext resolutionContext(boolean offline, TokenSupplier tokenSupplier, ExecutorService executor, PlatformEventListener listener) {
        return ResolutionContext.create(offline, tokenSupplier, executor, listener);
    }
}
