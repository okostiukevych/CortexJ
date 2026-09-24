package io.cortexj;

import io.cortexj.diagnostics.Support;
import io.cortexj.hardware.EnvironmentProfile;
import io.cortexj.hardware.HardwareProfile;
import io.cortexj.model.ModelReference;
import io.cortexj.planner.ExecutionRequest;
import io.cortexj.planner.Planner;
import io.cortexj.runtime.RuntimeDescriptor;
import io.cortexj.runtime.RuntimeHealthReport;

import java.util.List;

public interface CortexJ extends AutoCloseable {

    Models models();

    Planner planner();

    Support support();

    EnvironmentProfile environment();

    HardwareProfile hardware();

    List<RuntimeDescriptor> runtimes();

    List<RuntimeHealthReport> runtimeHealth();

    String platformModule();

    @Override
    void close();

    static CortexJBuilder builder() {
        return new CortexJBuilder();
    }
}
