package io.cortexj.spi.runtime;

import io.cortexj.hardware.EnvironmentProfile;
import io.cortexj.hardware.HardwareProfile;
import io.cortexj.model.ModelArtifact;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.planner.ExecutionRequest;

public final class RuntimeEvaluationRequest {

    private final ModelDescriptor model;
    private final ModelArtifact artifact;
    private final EnvironmentProfile environment;
    private final HardwareProfile hardware;
    private final ExecutionRequest userRequest;

    public RuntimeEvaluationRequest(ModelDescriptor model,
                                   ModelArtifact artifact,
                                   EnvironmentProfile environment,
                                   HardwareProfile hardware,
                                   ExecutionRequest userRequest) {
        this.model = model;
        this.artifact = artifact;
        this.environment = environment;
        this.hardware = hardware;
        this.userRequest = userRequest;
    }

    public ModelDescriptor model() {
        return model;
    }

    public ModelArtifact artifact() {
        return artifact;
    }

    public EnvironmentProfile environment() {
        return environment;
    }

    public HardwareProfile hardware() {
        return hardware;
    }

    public ExecutionRequest userRequest() {
        return userRequest;
    }
}
