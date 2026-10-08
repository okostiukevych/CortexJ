package io.cortexj.planner.test;

import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelReference;
import io.cortexj.model.TaskCapability;
import io.cortexj.planner.ExecutionRequest;
import io.cortexj.planner.internal.DefaultExecutionPlanner;
import io.cortexj.planner.internal.PlannerInputs;
import io.cortexj.spi.runtime.RuntimeProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Tag("perf")
class PlannerThroughputSoakTest {

    @Test
    void repeatedPlansStayFastAndBounded() {
        long started = System.nanoTime();
        List<RuntimeProvider> providers = new ArrayList<RuntimeProvider>();
        List<io.cortexj.model.ModelArtifact> artifacts = new ArrayList<io.cortexj.model.ModelArtifact>();
        for (int r = 0; r < 40; r++) {
            providers.add(new TestRuntimeProvider("runtime-" + r, ArtifactFormat.GGUF)
                    .devices(io.cortexj.runtime.DeviceType.CPU)
                    .task(TaskCapability.TEXT_GENERATION)
                    .maturity(io.cortexj.runtime.RuntimeDescriptor.Maturity.BETA));
        }
        for (int a = 0; a < 10; a++) {
            artifacts.add(TestRuntimeProvider.artifact(
                    "model-q" + a + ".gguf", ArtifactFormat.GGUF, 4_000_000_000L + a, "q6_k"));
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
                null, null, null, null, false, true, "0.1.0-test", 0L));
        ExecutionRequest request = ExecutionRequest.forModel(ModelReference.of("Qwen/Qwen3-8B"));
        for (int i = 0; i < 1500; i++) {
            planner.plan(request);
        }
        long millis = (System.nanoTime() - started) / 1_000_000L;
        System.out.println("1500 plans over 400 candidates: " + millis + " ms");
        // ~1 ms/plan on the reference machine; the ceiling guards against
        // future quadratic/allocation regressions, not absolute speed
        org.junit.jupiter.api.Assertions.assertTrue(millis < 60_000L,
                "1500 repeated plans must stay bounded, took " + millis + " ms");
    }
}
