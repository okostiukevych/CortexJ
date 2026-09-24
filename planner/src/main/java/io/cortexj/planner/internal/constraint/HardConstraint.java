package io.cortexj.planner.internal.constraint;

import io.cortexj.hardware.EnvironmentProfile;
import io.cortexj.hardware.HardwareProfile;
import io.cortexj.planner.ExecutionCandidate;
import io.cortexj.planner.PlanReason;
import io.cortexj.planner.ReasonCode;
import io.cortexj.runtime.RuntimeDescriptor;

public interface HardConstraint {

    PlanReason check(ExecutionCandidate candidate, ConstraintContext context);

    public static final class ConstraintContext {

        public final RuntimeDescriptor runtime;
        public final io.cortexj.spi.runtime.RuntimeProbeResult probe;
        public final EnvironmentProfile environment;
        public final HardwareProfile hardware;
        public final io.cortexj.planner.ExecutionRequest request;
        public final io.cortexj.model.ModelDescriptor model;
        public final boolean offline;
        public final boolean remoteAllowed;

        public ConstraintContext(RuntimeDescriptor runtime,
                                 io.cortexj.spi.runtime.RuntimeProbeResult probe,
                                 EnvironmentProfile environment,
                                 HardwareProfile hardware,
                                 io.cortexj.planner.ExecutionRequest request,
                                 io.cortexj.model.ModelDescriptor model,
                                 boolean offline,
                                 boolean remoteAllowed) {
            this.runtime = runtime;
            this.probe = probe;
            this.environment = environment;
            this.hardware = hardware;
            this.request = request;
            this.model = model;
            this.offline = offline;
            this.remoteAllowed = remoteAllowed;
        }
    }

    public static final class Violation {

        private Violation() {
        }

        public static PlanReason of(ReasonCode code, String message) {
            return PlanReason.negative(code, message);
        }
    }
}
