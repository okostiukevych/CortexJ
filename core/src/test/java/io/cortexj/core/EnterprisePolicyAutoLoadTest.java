package io.cortexj.core;

import io.cortexj.CortexJ;
import io.cortexj.diagnostics.SupportReport;
import io.cortexj.model.ModelReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnterprisePolicyAutoLoadTest {

    @TempDir
    Path tempDir;

    @Test
    void centrallySuppliedPolicyFileIsAutoAppliedToEveryPlan() throws Exception {
        Path work = tempDir.resolve("work");
        Files.createDirectories(work);
        Files.write(work.resolve("enterprise-policy.properties"),
                java.util.Arrays.asList("forbiddenRuntimes=e2e-runtime"));

        DefaultCortexJ platform = (DefaultCortexJ) CortexJ.builder()
                .repositoryProvider(new EndToEndLoadTest.FakeRepository())
                .runtimeProvider(new EndToEndLoadTest.FakeRuntimeProvider())
                .workingDirectory(work)
                .build();
        try {
            SupportReport report = platform.support()
                    .inspect(ModelReference.of("e2e/test-model"));
            assertFalse(report.isExecutable(),
                    "the enterprise policy must forbid the e2e runtime");
            boolean mentioned = false;
            for (SupportReport.RejectedCandidate rejected : report.rejectedCandidates()) {
                if (rejected.reason().message().contains("enterprise runtime policy")) {
                    mentioned = true;
                }
            }
            assertTrue(mentioned,
                    "the rejection must name the enterprise runtime policy, got: "
                            + report.rejectedCandidates());
        } finally {
            platform.close();
        }
    }

    @Test
    void absentPolicyFileChangesNothing() throws Exception {
        Path work = tempDir.resolve("clean-work");
        Files.createDirectories(work);
        assertFalse(Files.isRegularFile(work.resolve("enterprise-policy.properties")));

        DefaultCortexJ platform = (DefaultCortexJ) CortexJ.builder()
                .repositoryProvider(new EndToEndLoadTest.FakeRepository())
                .runtimeProvider(new EndToEndLoadTest.FakeRuntimeProvider())
                .workingDirectory(work)
                .build();
        try {
            SupportReport report = platform.support()
                    .inspect(ModelReference.of("e2e/test-model"));
            assertTrue(report.isExecutable(),
                    "without a policy file the plan must be unaffected");
        } finally {
            platform.close();
        }
    }

    @Test
    void unreadablePolicyFileFailsThePlatformBootstrap() throws Exception {
        Path work = tempDir.resolve("sabotaged-work");
        Files.createDirectories(work);
        Path policy = work.resolve("enterprise-policy.properties");
        Files.write(policy, java.util.Arrays.asList("forbiddenRuntimes=e2e-runtime"));
        // audit #100: a present-but-unreadable policy file fails closed
        org.junit.jupiter.api.Assumptions.assumeTrue(
                policy.toFile().setReadable(false, false),
                "the test needs chmod semantics - skipped explicitly on filesystems without them");
        try {
            org.junit.jupiter.api.Assertions.assertThrows(
                    io.cortexj.error.ConfigurationException.class,
                    () -> CortexJ.builder()
                            .repositoryProvider(new EndToEndLoadTest.FakeRepository())
                            .runtimeProvider(new EndToEndLoadTest.FakeRuntimeProvider())
                            .workingDirectory(work)
                            .build(),
                    "audit #100: a sabotaged (unreadable) policy file must fail the "
                            + "platform bootstrap instead of silently disabling the "
                            + "restrictions it declares");
        } finally {
            policy.toFile().setReadable(true, false);
        }
    }
}
