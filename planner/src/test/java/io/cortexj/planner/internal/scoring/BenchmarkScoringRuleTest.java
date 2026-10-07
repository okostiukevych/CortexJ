package io.cortexj.planner.internal.scoring;

import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.model.ModelReference;
import io.cortexj.planner.ExecutionCandidate;
import io.cortexj.planner.ExecutionRequest;
import io.cortexj.planner.benchmark.BenchmarkHints;
import io.cortexj.runtime.DeviceType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkScoringRuleTest {

    private static BenchmarkHints hints(String... lines) throws IOException {
        Path file = Files.createTempFile("benchmarks", ".properties");
        try {
            Files.write(file, java.util.Arrays.asList(lines));
            return BenchmarkHints.fromFile(file);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static ExecutionCandidate.Builder candidate(String runtime, String quantization,
                                                         DeviceType device) {
        return ExecutionCandidate.builder(runtime)
                .artifact(new ModelArtifact.Abstract("model.gguf", ArtifactFormat.GGUF,
                        1024L, null, null) {
                })
                .device(device)
                .quantization(quantization);
    }

    private static ExecutionRequest request() {
        return ExecutionRequest.forModel(ModelReference.of("Qwen/Qwen3-8B"));
    }

    @Test
    void matchingEntryAddsDeterministicBonusAndNote() throws IOException {
        BenchmarkHints file = hints(
                "bench.llamacpp,qwen3,*,cpu,throughputTokensPerSecond=85",
                "bench.onnx,qwen3,*,cpu,throughputTokensPerSecond=40",
                "bench.llamacpp,qwen3,*,cpu,latencyMillis=100");
        BenchmarkScoringRule rule = new BenchmarkScoringRule(file);

        ExecutionCandidate.Builder llamacpp = candidate("llamacpp", "q4_k_m", DeviceType.CPU);
        rule.apply(llamacpp, llamacpp.build(), null, request(), null);
        double expectedThroughputBonus = 15.0d * (85.0d / 85.0d);
        double expectedLatencyBonus = 10.0d * (100.0d / 100.0d);
        assertEquals(expectedThroughputBonus + expectedLatencyBonus,
                llamacpp.build().score(), 0.0001d);

        ExecutionCandidate.Builder onnx = candidate("onnx", "q4", DeviceType.CPU);
        rule.apply(onnx, onnx.build(), null, request(), null);
        double expectedOnnxThroughputBonus = 15.0d * (40.0d / 85.0d);
        assertEquals(expectedOnnxThroughputBonus, onnx.build().score(), 0.0001d,
                "the slower runtime must receive a proportionally smaller bonus");

        ExecutionCandidate built = llamacpp.build();
        assertTrue(built.notes().containsKey("benchmark:throughput"));
        assertTrue(built.notes().containsKey("benchmark:latency"));
        assertTrue(built.notes().get("benchmark:throughput").contains("85.0 t/s"));
    }

    @Test
    void nonMatchingEntryLeavesScoreUntouched() throws IOException {
        BenchmarkHints file = hints(
                "bench.llamacpp,phi-4,*,cpu,throughputTokensPerSecond=85");
        BenchmarkScoringRule rule = new BenchmarkScoringRule(file);
        ExecutionCandidate.Builder other = candidate("onnx", "q4", DeviceType.CPU);
        rule.apply(other, other.build(), null, request(), null);
        assertEquals(0.0d, other.build().score(), 0.0001d);
        assertFalse(other.build().notes().containsKey("benchmark:throughput"));
    }

    @Test
    void sameFileAlwaysProducesTheSameScores() throws IOException {
        String[] file = {
                "bench.llamacpp,qwen3,*,cpu,throughputTokensPerSecond=85",
                "bench.onnx,qwen3,*,cpu,throughputTokensPerSecond=40",
                "bench.onnx,qwen3,*,cpu,latencyMillis=180",
                "bench.llamacpp,qwen3,*,cpu,memoryGb=5.2"};
        double first = scoreOf(hints(file));
        double second = scoreOf(hints(file));
        assertEquals(first, second, 0.0d, "identical file content must produce identical scores");
    }

    private static double scoreOf(BenchmarkHints file) {
        BenchmarkScoringRule rule = new BenchmarkScoringRule(file);
        ExecutionCandidate.Builder builder = candidate("llamacpp", "q4_k_m", DeviceType.CPU);
        rule.apply(builder, builder.build(), null, request(), null);
        return builder.build().score();
    }

    @Test
    void emptyHintsAreNoOp() {
        BenchmarkScoringRule rule = new BenchmarkScoringRule(BenchmarkHints.empty());
        ExecutionCandidate.Builder builder = candidate("llamacpp", "q4_k_m", DeviceType.CPU);
        rule.apply(builder, builder.build(), null, request(), null);
        assertEquals(0.0d, builder.build().score(), 0.0001d);
    }
}
