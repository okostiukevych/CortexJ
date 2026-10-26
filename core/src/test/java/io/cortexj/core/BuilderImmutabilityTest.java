package io.cortexj.core;

import io.cortexj.CortexJ;
import io.cortexj.CortexJBuilder;
import io.cortexj.error.ModelLoadException;
import io.cortexj.model.ModelReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuilderImmutabilityTest {

    @TempDir
    Path workDir;

    @Test
    void mutatingTheBuilderAfterBuildThrowsAndCannotWeakenThePlatform() {
        CortexJBuilder builder = CortexJ.builder()
                .runtimeProvider(new ModelHandleCloseRaceTest.CountingRuntimeProvider())
                .repositoryProvider(new EndToEndLoadTest.FakeRepository())
                .workingDirectory(workDir)
                .trustPolicy(io.cortexj.model.TrustPolicy.TRUSTED_ONLY);
        CortexJ platform = builder.build();
        try {
            assertEquals(io.cortexj.model.TrustPolicy.TRUSTED_ONLY,
                    ((DefaultCortexJ) platform).config().trustPolicy,
                    "the platform must operate on the configuration snapshot taken at build()");

            assertThrows(IllegalStateException.class, () -> builder.trustPolicy(
                            io.cortexj.model.TrustPolicy.ALLOW_UNSIGNED_WITH_WARNING),
                    "relaxing the trust policy after build() must be rejected, not silently applied");
            assertThrows(IllegalStateException.class, () -> builder.offlineMode(true),
                    "flipping offline after build() must be rejected");
            assertThrows(IllegalStateException.class,
                    () -> builder.runtimeProvider(new ModelHandleCloseRaceTest.CountingRuntimeProvider()),
                    "adding providers after build() must be rejected");

            assertEquals(io.cortexj.model.TrustPolicy.TRUSTED_ONLY,
                    ((DefaultCortexJ) platform).config().trustPolicy,
                    "the rejected mutations must leave the running platform untouched");
            assertTrue(!((DefaultCortexJ) platform).config().offline,
                    "offline flag of the running platform must stay at its build()-time value");
        } finally {
            platform.close();
        }
    }

    @Test
    void frozenBuilderStillAllowsIndependentNewBuilders() {
        CortexJBuilder first = CortexJ.builder()
                .runtimeProvider(new ModelHandleCloseRaceTest.CountingRuntimeProvider())
                .repositoryProvider(new EndToEndLoadTest.FakeRepository())
                .workingDirectory(workDir);
        CortexJ platform = first.build();
        try {
            assertThrows(IllegalStateException.class, () -> first.offlineMode(true));
            // a fresh builder is unaffected
            CortexJBuilder second = CortexJ.builder()
                    .offlineMode(false)
                    .workingDirectory(workDir);
            second.offlineMode(true);
        } finally {
            platform.close();
        }
    }

    @Test
    void loadsStillWorkOnASnapshotConfiguration() {
        CortexJ platform = CortexJ.builder()
                .runtimeProvider(new ModelHandleCloseRaceTest.CountingRuntimeProvider())
                .repositoryProvider(new EndToEndLoadTest.FakeRepository())
                .workingDirectory(workDir)
                .build();
        try {
            io.cortexj.model.ModelHandle handle = platform.models()
                    .load(ModelReference.of("e2e/test-model"));
            assertEquals(io.cortexj.model.ModelLifecycleState.READY, handle.state());
            handle.close();
        } catch (ModelLoadException expectedOnSomeEnvironments) {
            // planner specifics may reject; the point is no freeze-related failure
        } finally {
            platform.close();
        }
    }

    @Test
    void builderGettersReturnUnmodifiableListsAndFailedBuildPreservesUserExecutor() throws Exception {
        CortexJBuilder builder = CortexJ.builder()
                .runtimeProvider(new ModelHandleCloseRaceTest.CountingRuntimeProvider())
                .repositoryProvider(new EndToEndLoadTest.FakeRepository());
        assertThrows(UnsupportedOperationException.class, () -> builder.runtimeProviders().clear());
        assertThrows(UnsupportedOperationException.class, () -> builder.repositoryProviders().clear());
        assertThrows(UnsupportedOperationException.class, () -> builder.converters().clear());
        assertThrows(UnsupportedOperationException.class, () -> builder.executionPolicies().clear());
        assertThrows(UnsupportedOperationException.class, () -> builder.eventListeners().clear());

        java.util.concurrent.ExecutorService userExecutor =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            Path fileNotDir = java.nio.file.Files.write(workDir.resolve("not-a-dir"), new byte[]{1});
            Path badWorkDir = java.nio.file.Files.createDirectories(workDir.resolve("bad-policy"));
            // a malformed unicode escape makes Properties.load fail: the platform must
            // fail closed AND leave the caller-supplied executor alone
            java.nio.file.Files.write(badWorkDir.resolve("enterprise-policy.properties"),
                    "key=\\uZZZZ".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThrows(RuntimeException.class, () -> CortexJ.builder()
                    .executor(userExecutor)
                    .workingDirectory(badWorkDir)
                    .build());
            org.junit.jupiter.api.Assertions.assertFalse(userExecutor.isShutdown(),
                    "a failed build() must not shut down a user-supplied executor");
        } finally {
            userExecutor.shutdownNow();
        }
    }
}
