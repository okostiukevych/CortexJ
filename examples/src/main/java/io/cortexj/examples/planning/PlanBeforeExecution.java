package io.cortexj.examples.planning;

import io.cortexj.CortexJ;
import io.cortexj.model.ModelReference;
import io.cortexj.planner.ExecutionPlan;
import io.cortexj.planner.ExecutionRequest;

/**
 * Plan before execution: one of the main UX scenarios.
 */
public final class PlanBeforeExecution {

    public static void main(String[] args) {
        try (CortexJ platform = CortexJ.builder().build()) {
            ExecutionPlan plan = platform.planner()
                    .plan(ExecutionRequest.forModel(ModelReference.of("Qwen/Qwen3-8B")));
            System.out.println(plan.explanation());
        }
    }

    private PlanBeforeExecution() {
    }
}
