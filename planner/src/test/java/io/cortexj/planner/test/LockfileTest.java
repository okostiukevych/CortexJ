package io.cortexj.planner.test;

import io.cortexj.error.PlanningException;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.Checksum;
import io.cortexj.model.ModelArtifact;
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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LockfileTest {

    @TempDir
    Path tempDir;

    private static final ModelReference MODEL = ModelReference.of("pinned/model");

    private io.cortexj.planner.internal.DefaultExecutionPlanner planner() {
        return planner(
                TestRuntimeProvider.artifact("model-q4_k.gguf", ArtifactFormat.GGUF, 1_000_000_000L, "q4_k"),
                TestRuntimeProvider.artifact("model-q6_k.gguf", ArtifactFormat.GGUF, 2_000_000_000L, "q6_k"));
    }

    private io.cortexj.planner.internal.DefaultExecutionPlanner planner(ModelArtifact... artifacts) {
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
                                new TestRepositoryProvider(artifacts)),
                        new java.util.ArrayList<io.cortexj.spi.conversion.ModelConverter>(),
                        new java.util.ArrayList<io.cortexj.spi.policy.ExecutionPolicy>(),
                        null,
                        TestRepositoryProvider.environment(25),
                        TestRepositoryProvider.hardware(false),
                        null, null, null, null,
                        false, true, "0.1.0-test", 0L));
    }

    private static ModelArtifact checksummedArtifact(String id, long sizeBytes, String sha256) {
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("quantization", "q6_k");
        return new ModelArtifact.Abstract(id, ArtifactFormat.GGUF, sizeBytes, Checksum.sha256(sha256), metadata) {
        };
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
    void pinnedPlanRejectsArtifactWithDifferentSha256() {
        io.cortexj.planner.internal.DefaultExecutionPlanner planner = planner(
                TestRuntimeProvider.artifact("model-q4_k.gguf", ArtifactFormat.GGUF, 1_000_000_000L, "q4_k"),
                checksummedArtifact("model-q6_k.gguf", 2_000_000_000L, "a1b2c3d4a1b2c3d4"));
        ModelLockfile lockfile = ModelLockfile.builder("pinned/model")
                .revision("testrev")
                .artifactFile("model-q6_k.gguf")
                .artifactSha256("deadbeefdeadbeef")
                .build();
        PlanningException exception = assertThrows(PlanningException.class,
                () -> planner.plan(ExecutionRequest.builder(MODEL)
                        .lockfile(lockfile)
                        .reproducibilityMode(ReproducibilityMode.PINNED_PLAN)
                        .build()));
        assertEquals("CORTEXJ-PLAN-4002", exception.code());
        assertTrue(exception.getMessage().contains("sha256 mismatch"),
                "error must name the sha256 mismatch: " + exception);
        assertTrue(exception.getMessage().contains("deadbeefdeadbeef"),
                "error must name the lockfile sha: " + exception);
        assertTrue(exception.getMessage().contains("a1b2c3d4a1b2c3d4"),
                "error must name the resolved sha: " + exception);
        assertEquals("deadbeefdeadbeef", exception.details().get("lockfileSha256"));
        assertEquals("a1b2c3d4a1b2c3d4", exception.details().get("resolvedSha256"));
    }

    @Test
    void pinnedPlanAcceptsArtifactWithMatchingSha256() {
        io.cortexj.planner.internal.DefaultExecutionPlanner planner = planner(
                TestRuntimeProvider.artifact("model-q4_k.gguf", ArtifactFormat.GGUF, 1_000_000_000L, "q4_k"),
                checksummedArtifact("model-q6_k.gguf", 2_000_000_000L, "cafebabecafebabe"));
        ModelLockfile lockfile = ModelLockfile.builder("pinned/model")
                .revision("testrev")
                .artifactFile("model-q6_k.gguf")
                .artifactSha256("CAFEBABECAFEBABE")
                .build();
        ExecutionPlan plan = planner.plan(ExecutionRequest.builder(MODEL)
                .lockfile(lockfile)
                .reproducibilityMode(ReproducibilityMode.PINNED_PLAN)
                .build());
        assertEquals("model-q6_k.gguf", plan.selected().artifact().id());
    }

    @Test
    void pinnedPlanRejectsArtifactWithoutChecksumAsUnverifiable() {
        io.cortexj.planner.internal.DefaultExecutionPlanner planner = planner();
        ModelLockfile lockfile = ModelLockfile.builder("pinned/model")
                .revision("testrev")
                .artifactFile("model-q4_k.gguf")
                .artifactSha256("deadbeefdeadbeef")
                .build();
        PlanningException exception = assertThrows(PlanningException.class,
                () -> planner.plan(ExecutionRequest.builder(MODEL)
                        .lockfile(lockfile)
                        .reproducibilityMode(ReproducibilityMode.PINNED_PLAN)
                        .build()));
        assertEquals("CORTEXJ-PLAN-4002", exception.code());
        assertTrue(exception.getMessage().contains("cannot be verified"),
                "error must explain the unverifiable pin: " + exception);
        assertTrue(exception.getMessage().contains("model-q4_k.gguf"),
                "error must name the pinned artifact: " + exception);
    }

    @Test
    void pinnedPlanWithoutSha256PinKeepsFilenameOnlyBehavior() {
        io.cortexj.planner.internal.DefaultExecutionPlanner planner = planner();
        ModelLockfile lockfile = ModelLockfile.builder("pinned/model")
                .revision("testrev")
                .artifactFile("model-q6_k.gguf")
                .build();
        ExecutionPlan plan = planner.plan(ExecutionRequest.builder(MODEL)
                .lockfile(lockfile)
                .reproducibilityMode(ReproducibilityMode.PINNED_PLAN)
                .build());
        assertEquals("model-q6_k.gguf", plan.selected().artifact().id());
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

    @Test
    void schemaVersionRoundTripsAndGatesMajors() {
        // audit (issue #159)
        ModelLockfile lockfile = ModelLockfile.builder("org/model")
                .revision("r1")
                .build();
        assertEquals("1.0", lockfile.schemaVersion());
        assertTrue(lockfile.toYaml().contains("lockfileVersion: 1.0"));
        ModelLockfile reread = ModelLockfile.parse(Arrays.asList(
                "lockfileVersion: 1.0", "model: org/model", "revision: r1"));
        assertEquals("1.0", reread.schemaVersion());
        // absent version = legacy file, still accepted
        ModelLockfile legacy = ModelLockfile.parse(Arrays.asList("model: org/model"));
        assertEquals("1.0", legacy.schemaVersion());
        // unknown major fails typed
        IllegalArgumentException unsupported = assertThrows(IllegalArgumentException.class,
                () -> ModelLockfile.parse(Arrays.asList(
                        "lockfileVersion: 2.0", "model: org/model")));
        assertTrue(unsupported.getMessage().contains("major version 2"));
        // unparsable version fails typed
        assertThrows(IllegalArgumentException.class,
                () -> ModelLockfile.parse(Arrays.asList(
                        "lockfileVersion: next", "model: org/model")));
    }
}
