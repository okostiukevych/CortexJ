package io.cortexj.core;

import io.cortexj.CortexJ;
import io.cortexj.diagnostics.SupportReport;
import io.cortexj.model.ModelReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkHintsAutoLoadTest {

    @TempDir
    Path tempDir;

    @Test
    void locallySuppliedBenchmarkHintsReachThePlanExplanation() throws Exception {
        Path work = tempDir.resolve("work");
        Files.createDirectories(work);
        Files.write(work.resolve("benchmarks.properties"),
                java.util.Arrays.asList(
                        "bench.e2e-runtime,e2e,*,cpu,throughputTokensPerSecond=100",
                        "bench.*,*,*,*,latencyMillis=250"));

        DefaultCortexJ platform = (DefaultCortexJ) CortexJ.builder()
                .repositoryProvider(new EndToEndLoadTest.FakeRepository())
                .runtimeProvider(new EndToEndLoadTest.FakeRuntimeProvider())
                .workingDirectory(work)
                .build();
        try {
            io.cortexj.planner.ExecutionPlan plan = platform.planner()
                    .plan(io.cortexj.planner.ExecutionRequest.forModel(
                            ModelReference.of("e2e/test-model")));
            String explanation = plan.explanation().toLowerCase(java.util.Locale.ROOT);
            assertTrue(explanation.contains("throughput") && explanation.contains("t/s"),
                    "the applied benchmark adjustment must be visible in the plan explanation: "
                            + plan.explanation());
            assertTrue(explanation.contains("29.88"),
                    "the benchmark-adjusted score must be reflected: " + plan.explanation());
        } finally {
            platform.close();
        }
    }

    @Test
    void absentHintsFileChangesNothing() throws Exception {
        Path work = tempDir.resolve("clean");
        Files.createDirectories(work);
        DefaultCortexJ platform = (DefaultCortexJ) CortexJ.builder()
                .repositoryProvider(new EndToEndLoadTest.FakeRepository())
                .runtimeProvider(new EndToEndLoadTest.FakeRuntimeProvider())
                .workingDirectory(work)
                .build();
        try {
            SupportReport report = platform.support()
                    .inspect(ModelReference.of("e2e/test-model"));
            assertTrue(report.isExecutable());
            assertTrue(!report.explanation().toLowerCase(java.util.Locale.ROOT).contains("throughput"),
                    "no benchmark file => no benchmark notes: " + report.explanation());
        } finally {
            platform.close();
        }
    }
}
