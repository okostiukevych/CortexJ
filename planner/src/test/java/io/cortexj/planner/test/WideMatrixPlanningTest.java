package io.cortexj.planner.test;

import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelReference;
import io.cortexj.model.TaskCapability;
import io.cortexj.planner.ExecutionPlan;
import io.cortexj.planner.ExecutionRequest;
import io.cortexj.planner.internal.DefaultExecutionPlanner;
import io.cortexj.planner.internal.PlannerInputs;
import io.cortexj.spi.runtime.RuntimeProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("perf")
class WideMatrixPlanningTest {

    private static final int RUNTIMES = 30;
    private static final int ARTIFACTS_PER_RUNTIME = 5;

    @Test
    void planOverWideCandidateMatrixStaysFast() {
        List<RuntimeProvider> providers = new ArrayList<RuntimeProvider>();
        List<io.cortexj.model.ModelArtifact> artifacts =
                new ArrayList<io.cortexj.model.ModelArtifact>();
        for (int r = 0; r < RUNTIMES; r++) {
            providers.add(new TestRuntimeProvider("runtime-" + r, ArtifactFormat.GGUF)
                    .devices(io.cortexj.runtime.DeviceType.CPU)
                    .task(TaskCapability.TEXT_GENERATION)
                    .maturity(io.cortexj.runtime.RuntimeDescriptor.Maturity.BETA));
        }
        for (int a = 0; a < ARTIFACTS_PER_RUNTIME; a++) {
            artifacts.add(TestRuntimeProvider.artifact(
                    "model-q" + a + "_k.gguf", ArtifactFormat.GGUF,
                    4_000_000_000L + a, "q" + a + "_k"));
        }

        DefaultExecutionPlanner planner = new DefaultExecutionPlanner(new PlannerInputs(
                providers,
                Arrays.<io.cortexj.spi.repository.ModelRepositoryProvider>asList(
                        new TestRepositoryProvider(artifacts.toArray(
                                new io.cortexj.model.ModelArtifact[0]))),
                new ArrayList<io.cortexj.spi.conversion.ModelConverter>(),
                new ArrayList<io.cortexj.spi.policy.ExecutionPolicy>(),
                null,
                TestRepositoryProvider.environment(25),
                TestRepositoryProvider.hardware(false),
                null,
                null,
                null,
                null,
                false,
                true,
                "0.1.0-test",
                0L));

        ExecutionRequest request = ExecutionRequest.forModel(ModelReference.of("Qwen/Qwen3-8B"));
        long started = System.nanoTime();
        ExecutionPlan plan = planner.plan(request);
        long millis = (System.nanoTime() - started) / 1_000_000L;

        assertTrue(plan.selected() != null);
        System.out.println("plan over " + (RUNTIMES * ARTIFACTS_PER_RUNTIME)
                + " candidates: " + millis + " ms");
        assertTrue(millis < 2_000L,
                "coarse ceiling: planning over " + (RUNTIMES * ARTIFACTS_PER_RUNTIME)
                        + " candidates must stay fast (took " + millis + " ms)");
    }

    @Test
    void planOverThousandCandidateMatrixStaysBounded() {
        // audit 2026-10-08: the scoring snapshot + artifact index must hold
        // past 1000 candidates without GC-death or quadratic blowup
        final int bigRuntimes = 100;
        final int bigArtifacts = 20;
        List<RuntimeProvider> providers = new ArrayList<RuntimeProvider>();
        List<io.cortexj.model.ModelArtifact> artifacts =
                new ArrayList<io.cortexj.model.ModelArtifact>();
        for (int r = 0; r < bigRuntimes; r++) {
            providers.add(new TestRuntimeProvider("runtime-" + r, ArtifactFormat.GGUF)
                    .devices(io.cortexj.runtime.DeviceType.CPU)
                    .task(TaskCapability.TEXT_GENERATION)
                    .maturity(io.cortexj.runtime.RuntimeDescriptor.Maturity.BETA));
        }
        for (int a = 0; a < bigArtifacts; a++) {
            artifacts.add(TestRuntimeProvider.artifact(
                    "model-q" + a + ".gguf", ArtifactFormat.GGUF,
                    4_000_000_000L + a, "q6_k"));
        }

        DefaultExecutionPlanner planner = new DefaultExecutionPlanner(new PlannerInputs(
                providers,
                Arrays.<io.cortexj.spi.repository.ModelRepositoryProvider>asList(
                        new TestRepositoryProvider(artifacts.toArray(
                                new io.cortexj.model.ModelArtifact[0]))),
                new ArrayList<io.cortexj.spi.conversion.ModelConverter>(),
                new ArrayList<io.cortexj.spi.policy.ExecutionPolicy>(),
                null,
                TestRepositoryProvider.environment(25),
                TestRepositoryProvider.hardware(false),
                null,
                null,
                null,
                null,
                false,
                true,
                "0.1.0-test",
                0L));

        ExecutionRequest request = ExecutionRequest.forModel(ModelReference.of("Qwen/Qwen3-8B"));
        long started = System.nanoTime();
        ExecutionPlan plan = planner.plan(request);
        long millis = (System.nanoTime() - started) / 1_000_000L;

        assertTrue(plan.selected() != null);
        System.out.println("plan over " + (bigRuntimes * bigArtifacts)
                + " candidates: " + millis + " ms");
        assertTrue(millis < 15_000L,
                "planning over " + (bigRuntimes * bigArtifacts)
                        + " candidates must stay bounded (took " + millis + " ms)");
    }
}
