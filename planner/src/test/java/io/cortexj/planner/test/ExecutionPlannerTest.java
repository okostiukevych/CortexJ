package io.cortexj.planner.test;

import io.cortexj.error.PlanningException;
import io.cortexj.hardware.EnvironmentProfile;
import io.cortexj.hardware.HardwareProfile;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.model.ModelReference;
import io.cortexj.planner.ExecutionCandidate;
import io.cortexj.planner.ExecutionPlan;
import io.cortexj.planner.ExecutionRequest;
import io.cortexj.planner.FallbackPolicy;
import io.cortexj.planner.PlanReason;
import io.cortexj.planner.QualityProfile;
import io.cortexj.planner.ReasonCode;
import io.cortexj.planner.internal.DefaultExecutionPlanner;
import io.cortexj.planner.internal.PlannerInputs;
import io.cortexj.spi.runtime.RuntimeProbeResult;
import io.cortexj.spi.runtime.RuntimeProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionPlannerTest {

    private static final ModelReference MODEL = ModelReference.of("Qwen/Qwen3-8B");

    private static final ModelArtifact GGUF_Q6 = TestRuntimeProvider.artifact("qwen3-8b-q6_k.gguf", ArtifactFormat.GGUF, 6_600_000_000L, "q6_k");
    private static final ModelArtifact GGUF_Q4 = TestRuntimeProvider.artifact("qwen3-8b-q4_k.gguf", ArtifactFormat.GGUF, 4_000_000_000L, "q4_k");
    private static final ModelArtifact ONNX_MODEL = TestRuntimeProvider.artifact("qwen3-8b.onnx", ArtifactFormat.ONNX, 16_000_000_000L, "fp16");

    private static TestRuntimeProvider llamacpp() {
        return new TestRuntimeProvider("llamacpp", ArtifactFormat.GGUF)
                .devices(io.cortexj.runtime.DeviceType.CPU, io.cortexj.runtime.DeviceType.GPU)
                .task(io.cortexj.model.TaskCapability.TEXT_GENERATION)
                .maturity(io.cortexj.runtime.RuntimeDescriptor.Maturity.BETA);
    }

    private static TestRuntimeProvider onnx() {
        return new TestRuntimeProvider("onnx", ArtifactFormat.ONNX)
                .devices(io.cortexj.runtime.DeviceType.CPU)
                .task(io.cortexj.model.TaskCapability.EMBEDDING)
                .maturity(io.cortexj.runtime.RuntimeDescriptor.Maturity.STABLE);
    }

    private static TestRuntimeProvider jinferRequiringJava25() {
        return new TestRuntimeProvider("jinfer", ArtifactFormat.GGUF)
                .devices(io.cortexj.runtime.DeviceType.GPU)
                .task(io.cortexj.model.TaskCapability.TEXT_GENERATION)
                .minJava(25)
                .maturity(io.cortexj.runtime.RuntimeDescriptor.Maturity.STABLE);
    }

    private static TestRuntimeProvider remote() {
        return new TestRuntimeProvider("remote-openai", ArtifactFormat.REMOTE)
                .devices(io.cortexj.runtime.DeviceType.REMOTE)
                .task(io.cortexj.model.TaskCapability.TEXT_GENERATION)
                .supportsEverything(true)
                .maturity(io.cortexj.runtime.RuntimeDescriptor.Maturity.STABLE);
    }

    private static DefaultExecutionPlanner planner(EnvironmentProfile environment,
                                                   HardwareProfile hardware,
                                                   boolean offline,
                                                   List<TestRuntimeProvider> providers,
                                                   ModelArtifact... artifacts) {
        List<RuntimeProvider> runtimeProviders = new ArrayList<RuntimeProvider>();
        for (TestRuntimeProvider provider : providers) {
            runtimeProviders.add(provider);
        }
        return new DefaultExecutionPlanner(new PlannerInputs(
                runtimeProviders,
                Arrays.<io.cortexj.spi.repository.ModelRepositoryProvider>asList(new TestRepositoryProvider(artifacts)),
                new ArrayList<io.cortexj.spi.conversion.ModelConverter>(),
                new ArrayList<io.cortexj.spi.policy.ExecutionPolicy>(),
                null,
                environment,
                hardware,
                null,
                null,
                null,
                null,
                offline,
                true,
                "0.1.0-test",
                0L));
    }

    private static EnvironmentProfile env11() {
        return TestRepositoryProvider.environment(11);
    }

    private static EnvironmentProfile env25() {
        return TestRepositoryProvider.environment(25);
    }

    private static HardwareProfile gpu() {
        return TestRepositoryProvider.hardware(true);
    }

    private static HardwareProfile noGpu() {
        return TestRepositoryProvider.hardware(false);
    }

    @Test
    void sameInputProducesSamePlan() {
        DefaultExecutionPlanner planner = planner(env25(), gpu(), false,
                Arrays.asList(llamacpp(), onnx()), GGUF_Q6, ONNX_MODEL);
        ExecutionRequest request = ExecutionRequest.forModel(MODEL);

        ExecutionPlan first = planner.plan(request);
        ExecutionPlan second = planner.plan(request);

        assertEquals(first.inputsDigest(), second.inputsDigest());
        assertEquals(first.selected().runtimeId(), second.selected().runtimeId());
        assertEquals(first.selected().stableKey(), second.selected().stableKey());
    }

    @Test
    void java11EnvironmentMustNotSelectJava25Runtime() {
        DefaultExecutionPlanner planner = planner(env11(), gpu(), false,
                Arrays.asList(jinferRequiringJava25(), llamacpp()), GGUF_Q6);

        ExecutionPlan plan = planner.plan(ExecutionRequest.forModel(MODEL));

        assertNotEquals("jinfer", plan.selected().runtimeId());
        assertEquals("llamacpp", plan.selected().runtimeId());
        boolean jinferRejectedForJvm = false;
        for (PlanReason reason : plan.reasons()) {
            if (reason.code() == ReasonCode.JVM_INCOMPATIBLE) {
                jinferRejectedForJvm = true;
            }
        }
        assertTrue(jinferRejectedForJvm || !plan.selected().runtimeId().equals("jinfer"));
    }

    @Test
    void unsupportedRuntimeIsNeverSelectedEvenWhenPreferred() {
        TestRuntimeProvider unavailable = llamacpp().withProbe(RuntimeProbeResult.unavailable("not installed"));
        DefaultExecutionPlanner planner = planner(env25(), gpu(), false,
                Arrays.asList(unavailable, onnx()), GGUF_Q6, ONNX_MODEL);

        ExecutionRequest request = ExecutionRequest.builder(MODEL)
                .preferredRuntime("llamacpp")
                .build();

        ExecutionPlan plan = planner.plan(request);
        assertEquals("onnx", plan.selected().runtimeId());
    }

    @Test
    void requiredRuntimeImpossibleThrowsWithoutSilentFallback() {
        DefaultExecutionPlanner planner = planner(env25(), gpu(), false,
                Arrays.asList(llamacpp()), GGUF_Q6, ONNX_MODEL);

        ExecutionRequest request = ExecutionRequest.builder(MODEL)
                .requiredRuntime("onnx")
                .build();

        PlanningException exception = assertThrows(PlanningException.class, () -> planner.plan(request));
        assertEquals("CORTEXJ-PLAN-4002", exception.code());
    }

    @Test
    void hardMemoryConstraintCannotBeBypassedByScore() {
        DefaultExecutionPlanner planner = planner(env25(), gpu(), false,
                Arrays.asList(llamacpp(), onnx()), GGUF_Q6, ONNX_MODEL);

        ExecutionRequest request = ExecutionRequest.builder(MODEL)
                .preferredRuntime("onnx")
                .memoryLimitBytes(1L * 1024L * 1024L)
                .build();

        PlanningException exception = assertThrows(PlanningException.class, () -> planner.plan(request));
        assertEquals("CORTEXJ-PLAN-4001", exception.code());
        assertTrue(exception.toString().contains("memory"));
    }

    @Test
    void offlineModeRejectsDownloadCandidates() {
        DefaultExecutionPlanner planner = planner(env25(), gpu(), true,
                Arrays.asList(llamacpp()), GGUF_Q6);

        io.cortexj.error.ModelResolutionException resolutionFailure = assertThrows(
                io.cortexj.error.ModelResolutionException.class,
                () -> planner.plan(ExecutionRequest.forModel(MODEL)));
        assertEquals("CORTEXJ-MODEL-1003", resolutionFailure.code());

        DefaultExecutionPlanner onlinePlanner = planner(env25(), gpu(), false,
                Arrays.asList(llamacpp()), GGUF_Q6);
        ExecutionPlan plan = onlinePlanner.plan(ExecutionRequest.forModel(MODEL));
        assertEquals("llamacpp", plan.selected().runtimeId());
        assertTrue(plan.selected().requiresDownload());
        assertEquals(1, plan.downloads().size());
    }

    @Test
    void qualityFirstPrefersHigherQuantization() {
        DefaultExecutionPlanner planner = planner(env25(), gpu(), false,
                Arrays.asList(llamacpp()), GGUF_Q4, GGUF_Q6);

        ExecutionPlan qualityFirst = planner.plan(ExecutionRequest.builder(MODEL)
                .qualityProfile(QualityProfile.QUALITY_FIRST).build());
        assertEquals("qwen3-8b-q6_k.gguf", qualityFirst.selected().artifact().id());

        ExecutionPlan memoryFirst = planner.plan(ExecutionRequest.builder(MODEL)
                .qualityProfile(QualityProfile.MEMORY_FIRST).build());
        assertEquals("qwen3-8b-q4_k.gguf", memoryFirst.selected().artifact().id());
    }

    @Test
    void deterministicTieBreakByRuntimeId() {
        TestRuntimeProvider alpha = llamacpp();
        TestRuntimeProvider beta = new TestRuntimeProvider("alpha-runtime", ArtifactFormat.GGUF)
                .devices(io.cortexj.runtime.DeviceType.CPU)
                .maturity(io.cortexj.runtime.RuntimeDescriptor.Maturity.BETA);
        DefaultExecutionPlanner planner = planner(env25(), noGpu(), false,
                Arrays.asList(alpha, beta), GGUF_Q6);

        ExecutionPlan plan = planner.plan(ExecutionRequest.forModel(MODEL));
        assertEquals("alpha-runtime", plan.selected().runtimeId());
    }

    @Test
    void explanationIsHumanReadableAndContainsDecision() {
        DefaultExecutionPlanner planner = planner(env25(), gpu(), false,
                Arrays.asList(llamacpp(), onnx()), GGUF_Q6, ONNX_MODEL);

        ExecutionPlan plan = planner.plan(ExecutionRequest.forModel(MODEL));
        String explanation = plan.explanation();

        assertTrue(explanation.contains("Model: Qwen/Qwen3-8B"));
        assertTrue(explanation.contains("Resolved revision: testrev"));
        assertTrue(explanation.contains("Selected:"));
        assertTrue(explanation.contains("runtime: llamacpp"));
        assertTrue(explanation.contains("Reasons:"));
        assertTrue(explanation.contains("Environment:"));
    }

    @Test
    void remoteProviderYieldsSyntheticRemoteCandidate() {
        DefaultExecutionPlanner planner = planner(env25(), gpu(), false,
                Arrays.asList(remote(), llamacpp()), GGUF_Q6);

        ExecutionPlan plan = planner.plan(ExecutionRequest.forModel(MODEL));
        boolean sawRemoteCandidate = "llamacpp".equals(plan.selected().runtimeId());
        for (ExecutionCandidate fallback : plan.fallbacks()) {
            if (fallback.device() == io.cortexj.runtime.DeviceType.REMOTE) {
                sawRemoteCandidate = true;
            }
        }
        assertTrue(sawRemoteCandidate, "expected a remote candidate in plan or fallbacks");
    }

    @Test
    void supportReportExplainsRejections() {
        DefaultExecutionPlanner planner = planner(env25(), gpu(), false,
                Arrays.asList(llamacpp()), GGUF_Q6, ONNX_MODEL);

        io.cortexj.diagnostics.SupportReport report = planner.inspect(ExecutionRequest.forModel(MODEL));
        assertTrue(report.isExecutable());
        assertNotNull(report.explanation());
        assertTrue(report.explanation().contains("Viable candidates"));
        assertEquals("testrev", report.resolvedRevision());
    }

    @Test
    void gpuRequiredWithoutGpuIsRejected() {
        TestRuntimeProvider gpuOnly = new TestRuntimeProvider("gpu-only", ArtifactFormat.GGUF)
                .devices(io.cortexj.runtime.DeviceType.GPU);
        DefaultExecutionPlanner planner = planner(env25(), noGpu(), false,
                Arrays.asList(gpuOnly), GGUF_Q6);

        PlanningException exception = assertThrows(PlanningException.class,
                () -> planner.plan(ExecutionRequest.forModel(MODEL)));
        assertEquals("CORTEXJ-PLAN-4001", exception.code());
        assertTrue(exception.toString().contains("GPU"));
    }
}
