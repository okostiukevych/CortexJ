package io.cortexj.planner.test;

import io.cortexj.error.PlanningException;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelReference;
import io.cortexj.model.TaskCapability;
import io.cortexj.planner.ExecutionRequest;
import io.cortexj.planner.PlanReason;
import io.cortexj.planner.ReasonCode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskCapabilityPlanningTest {

    private io.cortexj.planner.internal.DefaultExecutionPlanner planner(
            TaskCapability runtimeTask) {
        return new io.cortexj.planner.internal.DefaultExecutionPlanner(
                new io.cortexj.planner.internal.PlannerInputs(
                        Arrays.asList(
                                (io.cortexj.spi.runtime.RuntimeProvider) new TestRuntimeProvider("task-rt", ArtifactFormat.GGUF)
                                        .devices(io.cortexj.runtime.DeviceType.CPU)
                                        .task(runtimeTask)),
                        Collections.singletonList((io.cortexj.spi.repository.ModelRepositoryProvider)
                                new TestRepositoryProvider(
                                        TestRuntimeProvider.artifact("model.gguf", ArtifactFormat.GGUF, 1_000_000L, "q4_k"))),
                        new ArrayList<io.cortexj.spi.conversion.ModelConverter>(),
                        new ArrayList<io.cortexj.spi.policy.ExecutionPolicy>(),
                        null,
                        TestRepositoryProvider.environment(25),
                        TestRepositoryProvider.hardware(false),
                        null, null, null, null,
                        false, true, "0.1.0-test", 0L));
    }

    @Test
    void embeddingOnlyRuntimeCannotWinATextGenerationModel() {
        PlanningException impossible = assertThrows(PlanningException.class,
                () -> planner(TaskCapability.EMBEDDING)
                        .plan(ExecutionRequest.forModel(ModelReference.of("task/model"))));
        assertTrue(impossible.toString().contains("only offers [TEXT_GENERATION]"),
                "the failure must name the task mismatch, got: " + impossible);
    }

    @Test
    void matchingTaskStillPlans() {
        io.cortexj.planner.ExecutionPlan plan = planner(TaskCapability.TEXT_GENERATION)
                .plan(ExecutionRequest.forModel(ModelReference.of("task/model")));
        assertEquals("task-rt", plan.selected().runtimeId());
    }

    @Test
    void constraintReasonIsStable() {
        io.cortexj.error.PlanningException impossible = assertThrows(
                io.cortexj.error.PlanningException.class,
                () -> planner(TaskCapability.RERANKING)
                        .plan(ExecutionRequest.forModel(ModelReference.of("task/model"))));
        assertTrue(impossible.toString().contains("but the model only offers"),
                "stable task-mismatch reason required, got: " + impossible);
    }
}
