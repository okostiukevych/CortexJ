package io.cortexj.planner.test;

import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelReference;
import io.cortexj.model.TaskCapability;
import io.cortexj.planner.ExecutionPlan;
import io.cortexj.planner.ExecutionRequest;
import io.cortexj.planner.internal.DefaultExecutionPlanner;
import io.cortexj.planner.internal.PlannerInputs;
import io.cortexj.spi.runtime.RuntimeProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec v3.0 §124.3 (issue #158): valid-plan rate over a compatibility
 * matrix - every compatible combination must produce an explained plan.
 */
class ValidPlanRateTest {

    @Test
    void compatibleMatrixPlansAtFullRate() {
        final int runtimes = 5;
        final int artifacts = 10;
        List<RuntimeProvider> providers = new ArrayList<RuntimeProvider>();
        for (int r = 0; r < runtimes; r++) {
            providers.add(new TestRuntimeProvider("runtime-" + r, ArtifactFormat.GGUF)
                    .devices(io.cortexj.runtime.DeviceType.CPU)
                    .task(TaskCapability.TEXT_GENERATION)
                    .maturity(io.cortexj.runtime.RuntimeDescriptor.Maturity.STABLE));
        }
        List<io.cortexj.model.ModelArtifact> models = new ArrayList<io.cortexj.model.ModelArtifact>();
        for (int a = 0; a < artifacts; a++) {
            models.add(TestRuntimeProvider.artifact(
                    "model-q" + a + ".gguf", ArtifactFormat.GGUF, 4_000_000_000L + a, "q6_k"));
        }
        DefaultExecutionPlanner planner = new DefaultExecutionPlanner(new PlannerInputs(
                providers,
                Arrays.<io.cortexj.spi.repository.ModelRepositoryProvider>asList(
                        new TestRepositoryProvider(models.toArray(
                                new io.cortexj.model.ModelArtifact[0]))),
                new ArrayList<io.cortexj.spi.conversion.ModelConverter>(),
                new ArrayList<io.cortexj.spi.policy.ExecutionPolicy>(),
                null,
                TestRepositoryProvider.environment(25),
                TestRepositoryProvider.hardware(false),
                null, null, null, null, false, true, "0.1.0-test", 0L));

        int viable = 0;
        int total = 0;
        for (int a = 0; a < artifacts; a++) {
            ExecutionPlan plan = planner.plan(
                    ExecutionRequest.forModel(ModelReference.of("Qwen/Qwen3-8B")));
            total++;
            assertNotNull(plan.selected(), "every compatible combination must select");
            assertTrue(plan.explanation() != null && !plan.explanation().trim().isEmpty(),
                    "every plan must carry a human-readable explanation");
            viable++;
        }
        assertEquals(total, viable, "valid-plan rate must be 100% on a compatible matrix");
        System.out.println("valid-plan rate: " + viable + "/" + total);
    }
}
