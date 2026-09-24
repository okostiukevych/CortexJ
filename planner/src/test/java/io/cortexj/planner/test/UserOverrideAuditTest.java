package io.cortexj.planner.test;

import io.cortexj.error.PlanningException;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.model.ModelReference;
import io.cortexj.planner.ExecutionPlan;
import io.cortexj.planner.ExecutionRequest;
import io.cortexj.planner.ReasonCode;
import io.cortexj.runtime.DeviceType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserOverrideAuditTest {

    private static final ModelReference MODEL = ModelReference.of("audit/model");

    private static final ModelArtifact GGUF_Q4 = TestRuntimeProvider.artifact("model-q4_k.gguf", ArtifactFormat.GGUF, 4_000_000_000L, "q4_k");

    private io.cortexj.planner.internal.DefaultExecutionPlanner planner(boolean offline,
                                                                       boolean downloadsAllowed) {
        return new io.cortexj.planner.internal.DefaultExecutionPlanner(
                new io.cortexj.planner.internal.PlannerInputs(
                        Arrays.asList(
                                (io.cortexj.spi.runtime.RuntimeProvider) llamacpp(),
                                (io.cortexj.spi.runtime.RuntimeProvider) remote()),
                        Arrays.asList((io.cortexj.spi.repository.ModelRepositoryProvider) new TestRepositoryProvider(GGUF_Q4)),
                        new java.util.ArrayList<io.cortexj.spi.conversion.ModelConverter>(),
                        new java.util.ArrayList<io.cortexj.spi.policy.ExecutionPolicy>(),
                        null,
                        TestRepositoryProvider.environment(25),
                        TestRepositoryProvider.hardware(true),
                        null,
                        null,
                        null,
                        null,
                        offline,
                        true,
                        "0.1.0-test",
                        0L));
    }

    private static TestRuntimeProvider llamacpp() {
        return new TestRuntimeProvider("llamacpp", ArtifactFormat.GGUF)
                .devices(DeviceType.CPU)
                .task(io.cortexj.model.TaskCapability.TEXT_GENERATION);
    }

    private static TestRuntimeProvider remote() {
        return new TestRuntimeProvider("openai-remote", ArtifactFormat.REMOTE)
                .devices(DeviceType.REMOTE)
                .task(io.cortexj.model.TaskCapability.TEXT_GENERATION)
                .supportsEverything(true);
    }

    @Test
    void downloadsDisabledRejectsDownloadRequiringCandidates() {
        io.cortexj.planner.internal.DefaultExecutionPlanner planner = planner(false, false);

        PlanningException exception = assertThrows(PlanningException.class,
                () -> planner.plan(ExecutionRequest.builder(MODEL)
                        .downloadsAllowed(false)
                        .requiredRuntime("llamacpp")
                        .build()));
        assertEquals("CORTEXJ-PLAN-4002", exception.code());
        assertTrue(exception.toString().contains("download"));
    }

    @Test
    void downloadsDisabledStillAllowsRemoteCandidates() {
        io.cortexj.planner.internal.DefaultExecutionPlanner planner = planner(false, false);
        ExecutionPlan plan = planner.plan(ExecutionRequest.builder(MODEL)
                .downloadsAllowed(false)
                .preferredRuntime("openai-remote")
                .build());
        assertEquals("openai-remote", plan.selected().runtimeId());
        assertEquals(DeviceType.REMOTE, plan.selected().device());
    }

    @Test
    void requiredDeviceRejectsRemoteCandidates() {
        io.cortexj.planner.internal.DefaultExecutionPlanner planner = planner(false, true);
        PlanningException exception = assertThrows(PlanningException.class,
                () -> planner.plan(ExecutionRequest.builder(MODEL)
                        .requiredDevice(io.cortexj.planner.DevicePreference.CPU)
                        .forbiddenRuntime("llamacpp")
                        .build()));
        assertEquals("CORTEXJ-PLAN-4001", exception.code());
        assertTrue(exception.toString().contains("required device"));
    }

    @Test
    void allowedRepositoryExcludesOtherRepositories() {
        io.cortexj.planner.internal.DefaultExecutionPlanner planner = planner(false, true);
        io.cortexj.error.ModelResolutionException exception = assertThrows(
                io.cortexj.error.ModelResolutionException.class,
                () -> planner.plan(ExecutionRequest.builder(MODEL)
                        .allowedRepository("some-other-repo")
                        .build()));
        assertEquals("CORTEXJ-MODEL-1002", exception.code());
    }

    @Test
    void goldenDigestIsStableAcrossRuns() {
        io.cortexj.planner.internal.DefaultExecutionPlanner first = planner(false, true);
        io.cortexj.planner.internal.DefaultExecutionPlanner second = planner(false, true);
        ExecutionRequest request = ExecutionRequest.builder(MODEL)
                .preferredRuntime("llamacpp")
                .build();
        String firstDigest = first.plan(request).inputsDigest();
        String secondDigest = second.plan(request).inputsDigest();
        assertEquals(firstDigest, secondDigest, "digest must be reproducible (G-008, §67.4)");
        assertTrue(firstDigest.startsWith("sha256:"));
        assertEquals(71, firstDigest.length());
        assertNotEquals("", first.plan(request).explanation());
    }
}
