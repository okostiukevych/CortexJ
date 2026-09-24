package io.cortexj.spi.policy;

import io.cortexj.error.CortexJException;
import io.cortexj.planner.ExecutionCandidate;
import io.cortexj.planner.PlanReason;
import io.cortexj.planner.ReasonCode;

public interface ExecutionPolicy {

    String policyId();

    PolicyDecision evaluate(ExecutionCandidate candidate, PlanningContext context);

    static ExecutionPolicy reject(final String policyId, final ReasonCode reason, final String message) {
        return new ExecutionPolicy() {
            @Override
            public String policyId() {
                return policyId;
            }

            @Override
            public PolicyDecision evaluate(ExecutionCandidate candidate, PlanningContext context) {
                return PolicyDecision.reject(reason, message);
            }
        };
    }
}
