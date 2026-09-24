package io.cortexj.planner.test;

import io.cortexj.error.PlanningException;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelReference;
import io.cortexj.planner.ExecutionPlan;
import io.cortexj.planner.ExecutionRequest;
import io.cortexj.planner.ModelLockfile;
import io.cortexj.planner.ReproducibilityMode;
import io.cortexj.runtime.DeviceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LockfileTest {

    @TempDir
    Path tempDir;

    private static final ModelReference MODEL = ModelReference.of("pinned/model");

    private io.cortexj.planner.internal.DefaultExecutionPlanner planner() {
        return new io.cortexj.planner.internal.DefaultExecutionPlanner(
                new io.cortexj.planner.internal.PlannerInputs(
                        Arrays.asList(
                                (io.cortexj.spi.runtime.RuntimeProvider) new TestRuntimeProvider("aa-runtime", ArtifactFormat.GGUF)
                                        .devices(DeviceType.CPU)
                                        .task(io.cortexj.model.TaskCapability.TEXT_GENERATION),
                                (io.cortexj.spi.runtime.RuntimeProvider) new TestRuntimeProvider("llamacpp", ArtifactFormat.GGUF)
                                        .devices(DeviceType.CPU)
                                        .task(io.cortexj.model.TaskCapability.TEXT_GENERATION)),
                        Arrays.asList((io.cortexj.spi.repository.ModelRepositoryProvider)
                                new TestRepositoryProvider(
                                        TestRuntimeProvider.artifact("model-q4_k.gguf", ArtifactFormat.GGUF, 1_000_000_000L, "q4_k"),
                                        TestRuntimeProvider.artifact("model-q6_k.gguf", ArtifactFormat.GGUF, 2_000_000_000L, "q6_k"))),
                        new java.util.ArrayList<io.cortexj.spi.conversion.ModelConverter>(),
                        new java.util.ArrayList<io.cortexj.spi.policy.ExecutionPolicy>(),
                        null,
                        TestRepositoryProvider.environment(25),
                        TestRepositoryProvider.hardware(false),
                        null, null, null, null,
                        false, true, "0.1.0-test", 0L));
    }

    @Test
    void pinnedPlanForcesLockedCandidateEvenIfScoredLower() {
        ExecutionPlan free = planner().plan(ExecutionRequest.forModel(MODEL));
        String freeWinner = free.selected().runtimeId();

        ModelLockfile lockfile = ModelLockfile.fromPlan(free).withRuntimeId("llamacpp");
        if ("llamacpp".equals(freeWinner)) {
            lockfile = lockfile.withRuntimeId("aa-runtime");
        }
        ExecutionPlan pinned = planner().plan(ExecutionRequest.builder(MODEL)
                .lockfile(lockfile)
                .reproducibilityMode(ReproducibilityMode.PINNED_PLAN)
                .build());

        assertEquals(lockfile.runtimeId(), pinned.selected().runtimeId());
        assertNotEqualsOrIgnore(freeWinner, pinned.selected().runtimeId());
    }

    private static void assertNotEqualsOrIgnore(String a, String b) {
        assertTrue(!a.equals(b), "pinned plan must be able to differ from the free plan");
    }

    @Test
    void pinnedPlanWithImpossibleLockThrowsActionableError() {
        ModelLockfile lockfile = ModelLockfile.builder("pinned/model")
                .revision("testrev")
                .runtimeId("no-such-runtime")
                .artifactFile("model-q4_k.gguf")
                .build();
        PlanningException exception = assertThrows(PlanningException.class,
                () -> planner().plan(ExecutionRequest.builder(MODEL)
                        .lockfile(lockfile)
                        .reproducibilityMode(ReproducibilityMode.PINNED_PLAN)
                        .build()));
        assertEquals("CORTEXJ-PLAN-4002", exception.code());
        assertTrue(exception.toString().contains("no-such-runtime"));
    }

    @Test
    void pinnedModelRevisionMismatchDetected() {
        ModelLockfile lockfile = ModelLockfile.builder("pinned/model")
                .revision("different-revision")
                .build();
        PlanningException exception = assertThrows(PlanningException.class,
                () -> planner().plan(ExecutionRequest.builder(MODEL)
                        .lockfile(lockfile)
                        .reproducibilityMode(ReproducibilityMode.PINNED_MODEL)
                        .build()));
        assertEquals("CORTEXJ-PLAN-4002", exception.code());
        assertTrue(exception.toString().contains("different-revision"));
    }

    @Test
    void lockfileRoundTripPreservesFields() throws Exception {
        ModelLockfile lockfile = ModelLockfile.builder("org/model")
                .revision("7f62abc")
                .artifactFile("qwen3-8b-q6_k.gguf")
                .artifactSha256("deadbeef")
                .runtimeId("LlamaCpp")
                .platformVersion("0.1.0")
                .plannerDigest("sha256:abc")
                .build();
        Path file = tempDir.resolve("cortexj.lock");
        lockfile.writeTo(file);
        ModelLockfile read = ModelLockfile.readFrom(file);

        assertEquals("org/model", read.model());
        assertEquals("7f62abc", read.revision());
        assertEquals("qwen3-8b-q6_k.gguf", read.artifactFile());
        assertEquals("deadbeef", read.artifactSha256());
        assertEquals("llamacpp", read.runtimeId());
        assertEquals("0.1.0", read.platformVersion());
        assertEquals("sha256:abc", read.plannerDigest());
        assertTrue(read.pinsArtifact());
        assertTrue(read.pinsRuntime());
        assertTrue(read.toYaml().contains("sha256: deadbeef"));
    }

    @Test
    void malformedLockfileRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ModelLockfile.parse(Arrays.asList("model", "no colon here")));
        assertThrows(IllegalArgumentException.class,
                () -> ModelLockfile.parse(Arrays.asList("revision: r1")));
    }
}
