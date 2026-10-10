package io.cortexj.planner.test;

import io.cortexj.hardware.EnvironmentProfile;
import io.cortexj.hardware.HardwareProfile;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.model.ModelReference;
import io.cortexj.planner.ExecutionPlan;
import io.cortexj.planner.ExecutionRequest;
import io.cortexj.planner.internal.DefaultExecutionPlanner;
import io.cortexj.planner.internal.PlannerInputs;
import io.cortexj.spi.runtime.RuntimeProbeResult;
import io.cortexj.spi.runtime.RuntimeProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-model verification 2026-10-10: a DEGRADED runtime (dead remote
 * endpoint) outscored a healthy local one for a local GGUF file, so
 * {@code run ./model.gguf} failed with HTTP 404 instead of executing
 * locally. Degraded candidates must lose to healthy ones while staying
 * selectable via explicit --runtime/--prefer-runtime.
 */
class ProbeHealthScoringTest {

    private static final ModelReference MODEL = ModelReference.of("Qwen/Qwen3-8B");

    private static final ModelArtifact GGUF_Q4 = TestRuntimeProvider.artifact(
            "qwen3-8b-q4_k.gguf", ArtifactFormat.GGUF, 4_000_000_000L, "q4_k");

    private static TestRuntimeProvider local(String id, RuntimeProbeResult probe) {
        return new TestRuntimeProvider(id, ArtifactFormat.GGUF)
                .devices(io.cortexj.runtime.DeviceType.CPU)
                .task(io.cortexj.model.TaskCapability.TEXT_GENERATION)
                .maturity(io.cortexj.runtime.RuntimeDescriptor.Maturity.STABLE)
                .withProbe(probe);
    }

    private static TestRuntimeProvider remote(RuntimeProbeResult probe) {
        return new TestRuntimeProvider("remote-openai", ArtifactFormat.REMOTE)
                .devices(io.cortexj.runtime.DeviceType.REMOTE)
                .task(io.cortexj.model.TaskCapability.TEXT_GENERATION)
                .supportsEverything(true)
                .maturity(io.cortexj.runtime.RuntimeDescriptor.Maturity.STABLE)
                .withProbe(probe);
    }

    private static DefaultExecutionPlanner planner(List<TestRuntimeProvider> providers,
                                                   ModelArtifact... artifacts) {
        List<RuntimeProvider> runtimeProviders = new ArrayList<RuntimeProvider>();
        for (TestRuntimeProvider provider : providers) {
            runtimeProviders.add(provider);
        }
        EnvironmentProfile environment = TestRepositoryProvider.environment(25);
        HardwareProfile hardware = TestRepositoryProvider.hardware(false);
        return new DefaultExecutionPlanner(new PlannerInputs(
                runtimeProviders,
                Arrays.<io.cortexj.spi.repository.ModelRepositoryProvider>asList(
                        new TestRepositoryProvider(artifacts)),
                new ArrayList<io.cortexj.spi.conversion.ModelConverter>(),
                new ArrayList<io.cortexj.spi.policy.ExecutionPolicy>(),
                null,
                environment,
                hardware,
                null,
                null,
                null,
                null,
                false,
                true,
                "0.1.0-test",
                0L));
    }

    @Test
    void degradedCandidateLosesToIdenticalHealthyCandidate() {
        DefaultExecutionPlanner planner = planner(Arrays.asList(
                local("local-a", RuntimeProbeResult.available("healthy")),
                local("local-b", RuntimeProbeResult.degraded("flaky"))),
                GGUF_Q4);
        ExecutionPlan plan = planner.plan(ExecutionRequest.forModel(MODEL));
        assertEquals("local-a", plan.selected().runtimeId(),
                "a degraded runtime must lose to an otherwise identical healthy one");
        assertEquals(2.0d,
                plan.selected().score() - plan.fallbacks().get(0).score(), 1e-9,
                "the only score difference must be the degraded penalty");
    }

    @Test
    void degradedRemoteLosesToHealthyLocal() {
        DefaultExecutionPlanner planner = planner(Arrays.asList(
                local("llamacpp", RuntimeProbeResult.available("llama-server found")),
                remote(RuntimeProbeResult.degraded("endpoint responded with HTTP 404"))),
                GGUF_Q4);
        ExecutionPlan plan = planner.plan(ExecutionRequest.forModel(MODEL));
        assertEquals("llamacpp", plan.selected().runtimeId(),
                "a degraded remote must not beat a healthy local runtime");
        assertTrue(plan.selected().notes().toString().contains("probeHealth=AVAILABLE")
                        || plan.explanation().contains("llamacpp"),
                "the plan explanation must show the healthy winner");
    }

    @Test
    void requiredRuntimeStillForcesDegraded() {
        DefaultExecutionPlanner planner = planner(Arrays.asList(
                local("llamacpp", RuntimeProbeResult.available("llama-server found")),
                remote(RuntimeProbeResult.degraded("endpoint responded with HTTP 404"))),
                GGUF_Q4);
        ExecutionRequest request = ExecutionRequest.builder(MODEL)
                .requiredRuntime("remote-openai")
                .build();
        ExecutionPlan plan = planner.plan(request);
        assertEquals("remote-openai", plan.selected().runtimeId(),
                "an explicit --runtime requirement must still select the degraded runtime");
    }

    @Test
    void preferredRuntimeOverridesDegradedPenalty() {
        DefaultExecutionPlanner planner = planner(Arrays.asList(
                local("llamacpp", RuntimeProbeResult.available("llama-server found")),
                remote(RuntimeProbeResult.degraded("endpoint responded with HTTP 404"))),
                GGUF_Q4);
        ExecutionRequest request = ExecutionRequest.builder(MODEL)
                .preferredRuntime("remote-openai")
                .build();
        ExecutionPlan plan = planner.plan(request);
        assertEquals("remote-openai", plan.selected().runtimeId(),
                "an explicit --prefer-runtime (+3.0) must override the degraded penalty (-2.0)");
    }

    @Test
    void degradedWinnerProducesWarning() {
        DefaultExecutionPlanner planner = planner(Arrays.asList(
                remote(RuntimeProbeResult.degraded("endpoint responded with HTTP 404"))),
                GGUF_Q4);
        ExecutionPlan plan = planner.plan(ExecutionRequest.forModel(MODEL));
        assertEquals("remote-openai", plan.selected().runtimeId());
        boolean warned = false;
        for (io.cortexj.planner.PlanReason warning : plan.warnings()) {
            if (warning.message().contains("remote-openai")
                    && warning.message().contains("DEGRADED")) {
                warned = true;
            }
        }
        assertTrue(warned, "running on a degraded winner must warn explicitly");
    }

    @Test
    void noCandidateCarriesRuntimeHealth() {
        TestRuntimeProvider ancient = new TestRuntimeProvider("ancient", ArtifactFormat.GGUF)
                .devices(io.cortexj.runtime.DeviceType.CPU)
                .task(io.cortexj.model.TaskCapability.TEXT_GENERATION)
                .minJava(99);
        DefaultExecutionPlanner planner = planner(Arrays.asList(ancient), GGUF_Q4);
        io.cortexj.error.PlanningException failure = null;
        try {
            planner.plan(ExecutionRequest.forModel(MODEL));
        } catch (io.cortexj.error.PlanningException e) {
            failure = e;
        }
        assertTrue(failure != null, "plan with no viable candidate must throw");
        assertTrue(failure.details().containsKey("runtimeHealth"),
                "the error must carry runtime health lines, got: " + failure.details().keySet());
        assertTrue(failure.details().get("runtimeHealth").contains("ancient"),
                "health lines must name the runtime, got: " + failure.details().get("runtimeHealth"));
    }
}
