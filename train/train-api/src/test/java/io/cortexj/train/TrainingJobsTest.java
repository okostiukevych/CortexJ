package io.cortexj.train;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Orchestration contract: honest compatibility reports, fail-fast before
 * allocation, explicit backend requirement respected, typed failure when
 * nothing supports the run.
 */
class TrainingJobsTest {

    private static TrainingRequest lora() {
        return TrainingRequest.builder()
                .model("Qwen/Qwen3-8B")
                .dataset("sha256:train")
                .strategy(TrainingStrategy.LORA)
                .epochs(3)
                .learningRate(2e-4)
                .build();
    }

    private static TrainingBackend full(String id) {
        return new TrainingBackend() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Support evaluate(TrainingRequest request) {
                return new Support(Level.FULL, "synthetic full support", 1024);
            }

            @Override
            public TrainingResult train(TrainingRequest request, Support support) {
                return new TrainingResult("job-1",
                        TrainingJobs.identityFor(request, "rev"),
                        "/tmp/adapter.safetensors",
                        Map.of("loss", 0.42),
                        new TrainingResult.ProvenanceHolder("sha256:train",
                                List.of(42L), id, "cpu",
                                Map.of("adapter.safetensors", "sha256:out")));
            }
        };
    }

    private static TrainingBackend unsupported(String id, String reason) {
        return new TrainingBackend() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Support evaluate(TrainingRequest request) {
                return new Support(Level.UNSUPPORTED, reason, 0);
            }

            @Override
            public TrainingResult train(TrainingRequest request, Support support) {
                throw new IllegalStateException("never reached");
            }
        };
    }

    @Test
    void evaluateReportsEveryBackendHonestly() {
        TrainingJobs jobs = new TrainingJobs(Arrays.asList(
                full("alpha"), unsupported("beta", "engine missing")));
        List<TrainingBackend.Support> report = jobs.evaluate(lora());
        assertEquals(2, report.size());
        assertTrue(report.get(0).isSupported());
        assertEquals(1024, report.get(0).estimatedMemoryBytes());
        assertTrue(!report.get(1).isSupported());
        assertEquals("engine missing", report.get(1).reason());
    }

    @Test
    void startPicksFirstFullySupported() {
        TrainingJobs jobs = new TrainingJobs(Arrays.asList(
                unsupported("beta", "engine missing"), full("alpha")));
        TrainingResult result = jobs.start(lora());
        assertEquals("alpha", result.provenance().backend());
        assertEquals("Qwen/Qwen3-8B", result.model().id());
        assertEquals("rev", result.model().revision());
        assertEquals(0.42, result.metrics().get("loss"), 1e-12);
        assertEquals("sha256:train", result.provenance().datasetFingerprint());
    }

    @Test
    void startFailsTypedWhenNothingSupports() {
        TrainingJobs jobs = new TrainingJobs(Arrays.asList(
                unsupported("beta", "engine missing")));
        io.cortexj.error.CortexJException failure = assertThrows(
                io.cortexj.error.CortexJException.class, () -> jobs.start(lora()));
        assertEquals("CORTEXJ-PLAN-4001", failure.code());
        assertTrue(failure.details().get("declines").contains("engine missing"));
    }

    @Test
    void requiredBackendIsRespectedNeverSilentlyIgnored() {
        TrainingJobs jobs = new TrainingJobs(Arrays.asList(full("alpha"), full("beta")));
        TrainingRequest required = TrainingRequest.builder()
                .model("m").dataset("d").parameter("requireBackend", "beta").build();
        TrainingResult result = jobs.start(required);
        assertEquals("beta", result.provenance().backend());
        // required backend missing -> typed failure, no silent fallback
        TrainingJobs others = new TrainingJobs(Arrays.asList(full("alpha")));
        assertThrows(io.cortexj.error.CortexJException.class, () -> others.start(required));
    }
}
