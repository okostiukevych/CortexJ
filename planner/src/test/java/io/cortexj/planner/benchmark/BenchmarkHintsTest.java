package io.cortexj.planner.benchmark;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkHintsTest {

    private static BenchmarkHints load(String... lines) throws IOException {
        Path file = Files.createTempFile("benchmarks", ".properties");
        try {
            Files.write(file, java.util.Arrays.asList(lines));
            return BenchmarkHints.fromFile(file);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void emptyFileIsEmpty() throws IOException {
        BenchmarkHints hints = load();
        assertTrue(hints.isEmpty());
        assertNull(hints.best("llamacpp", "Qwen/Qwen3-8B", "q4_k_m", "cpu",
                BenchmarkHints.METRIC_THROUGHPUT));
    }

    @Test
    void exactEntriesMatchAndLookupWorks() throws IOException {
        BenchmarkHints hints = load(
                "bench.llamacpp,qwen3,q4_k_m,cpu,throughputTokensPerSecond=85",
                "bench.onnx,qwen3,q4,cpu,throughputTokensPerSecond=40",
                "bench.onnx,qwen3,q4,cpu,latencyMillis=180");
        assertEquals(Double.valueOf(85.0d),
                hints.best("llamacpp", "Qwen/Qwen3-8B", "q4_k_m", "cpu",
                        BenchmarkHints.METRIC_THROUGHPUT));
        assertEquals(Double.valueOf(180.0d),
                hints.best("onnx", "Qwen/Qwen3-8B", "q4", "cpu",
                        BenchmarkHints.METRIC_LATENCY));
        assertEquals(85.0d, hints.maxOf(BenchmarkHints.METRIC_THROUGHPUT));
        assertEquals(180.0d, hints.minOf(BenchmarkHints.METRIC_LATENCY));
    }

    @Test
    void wildcardsMatchAnything() throws IOException {
        BenchmarkHints hints = load(
                "bench.*,qwen3,*,*,throughputTokensPerSecond=50",
                "bench.llamacpp,*,*,*,latencyMillis=120");
        assertEquals(Double.valueOf(50.0d),
                hints.best("whatever-runtime", "Qwen3-anything", "any-quant", "gpu",
                        BenchmarkHints.METRIC_THROUGHPUT));
        assertEquals(Double.valueOf(120.0d),
                hints.best("llamacpp", "any/model", "", "cpu",
                        BenchmarkHints.METRIC_LATENCY));
    }

    @Test
    void moreSpecificEntryWinsOverWildcard() throws IOException {
        BenchmarkHints hints = load(
                "bench.*,*,*,*,throughputTokensPerSecond=10",
                "bench.llamacpp,*,*,*,throughputTokensPerSecond=99");
        assertEquals(Double.valueOf(99.0d),
                hints.best("llamacpp", "m", "q", "cpu", BenchmarkHints.METRIC_THROUGHPUT));
        assertEquals(Double.valueOf(10.0d),
                hints.best("onnx", "m", "q", "cpu", BenchmarkHints.METRIC_THROUGHPUT));
    }

    @Test
    void familyMatchesAsCaseInsensitiveSubstring() throws IOException {
        BenchmarkHints hints = load(
                "bench.*,qwen3,*,*,latencyMillis=90");
        assertEquals(Double.valueOf(90.0d),
                hints.best("llamacpp", "QWEN/QWEN3-8B", "", "cpu",
                        BenchmarkHints.METRIC_LATENCY));
        assertNull(hints.best("llamacpp", "llama/llama-3", "", "cpu",
                BenchmarkHints.METRIC_LATENCY));
    }

    @Test
    void malformedEntriesAreCollectedAsWarningsNotFailures() throws IOException {
        Properties properties = new Properties();
        properties.setProperty("bench.only-three-parts,cpu,throughputTokensPerSecond", "1");
        properties.setProperty("bench.onnx,qwen,cpu,unknownMetric", "5");
        properties.setProperty("bench.onnx,qwen,cpu,cpu,latencyMillis", "not-a-number");
        properties.setProperty("bench.onnx,qwen,cpu,cpu,memoryGb", "-3");
        properties.setProperty("unrelated.key", "7");
        BenchmarkHints hints = BenchmarkHints.fromProperties(properties);
        assertTrue(hints.isEmpty(), "no valid entries must survive");
        assertEquals(4, hints.warnings().size(), String.valueOf(hints.warnings()));
        boolean sawMalformed = false;
        for (String warning : hints.warnings()) {
            if (warning.contains("malformed benchmark entry")) {
                sawMalformed = true;
            }
        }
        assertTrue(sawMalformed, "a wrong segment count must produce a malformed warning: "
                + hints.warnings());
    }
}
