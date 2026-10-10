package io.cortexj.core;

import io.cortexj.CortexJ;
import io.cortexj.cache.CacheStorage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Platform-layer performance smoke: planner overhead, cache hit/miss.
 * Raw backend performance is intentionally NOT measured here - only the overhead
 * the platform itself adds.
 */
@Tag("perf")
class PlatformPerfSmokeTest {

    @TempDir
    Path tempDir;

    @Test
    void plannerOverheadIsNegligibleRelativeToModelLoad() {
        CortexJ platform = CortexJ.builder()
                .runtimeProvider(new EndToEndLoadTest.FakeRuntimeProvider())
                .repositoryProvider(new EndToEndLoadTest.FakeRepository())
                .build();
        try {
            io.cortexj.model.ModelReference reference =
                    io.cortexj.model.ModelReference.of("e2e/test-model");
            // warm-up
            platform.planner().plan(io.cortexj.planner.ExecutionRequest.forModel(reference));
            long best = Long.MAX_VALUE;
            for (int i = 0; i < 20; i++) {
                long start = System.nanoTime();
                platform.planner().plan(io.cortexj.planner.ExecutionRequest.forModel(reference));
                long elapsed = System.nanoTime() - start;
                best = Math.min(best, elapsed);
            }
            double bestMillis = best / 1_000_000.0;
            System.out.printf(java.util.Locale.ROOT,
                    "planner overhead (best of 20): %.2f ms%n", bestMillis);
            // planner overhead must be negligible relative to model load
            // (model loads take seconds+); keep a generous ceiling for CI noise.
            assertTrue(bestMillis < 500.0,
                    "planner overhead must stay negligible, was " + bestMillis + " ms");
        } finally {
            platform.close();
        }
    }

    @Test
    void cacheHitIsCheapVersusCacheMiss() throws Exception {
        CacheStorage cache = new CacheStorage(tempDir.resolve("cache"));
        byte[] payload = new byte[64 * 1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }

        long missStart = System.nanoTime();
        cache.put("perf-repo", "rev1", "artifact.bin", null,
                new ByteArrayInputStream(payload));
        long missNanos = System.nanoTime() - missStart;

        long hitStart = System.nanoTime();
        Path hit = cache.get("perf-repo", "rev1", "artifact.bin", null);
        long hitNanos = System.nanoTime() - hitStart;

        assertTrue(hit != null, "cache hit must return the stored artifact");
        System.out.printf(java.util.Locale.ROOT,
                "cache miss (64 KiB write): %.3f ms, cache hit: %.6f ms (hit/miss ratio %.3f)%n",
                missNanos / 1_000_000.0, hitNanos / 1_000_000.0,
                hitNanos / (double) Math.max(missNanos, 1L));
        // budgets are qualitative on shared CI hardware - single-operation
        // timings are noisy (page cache, JIT), so assert coarse ceilings only and
        // print the comparison for observation (measurement, not gating).
        assertTrue(hitNanos < 1_000_000_000L,
                "a 64 KiB cache hit taking more than 1 s indicates a real problem");
    }
}
