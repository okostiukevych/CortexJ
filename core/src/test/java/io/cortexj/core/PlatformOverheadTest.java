package io.cortexj.core;

import io.cortexj.CortexJ;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelReference;
import io.cortexj.model.TrustPolicy;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Platform dispatch overhead harness (issue #158). The
 * fake runtime answers instantly, so measured time is pure platform
 * overhead (handle dispatch, events, metrics) - it must stay flat and
 * far below any real inference cost.
 */
@Tag("perf")
class PlatformOverheadTest {

    @TempDir
    Path workDir;

    @Test
    void dispatchOverheadStaysFlat() throws Exception {
        CortexJ platform = CortexJ.builder()
                .runtimeProvider(new EndToEndLoadTest.FakeRuntimeProvider())
                .repositoryProvider(new EndToEndLoadTest.FakeRepository())
                .workingDirectory(workDir)
                .trustPolicy(TrustPolicy.ALLOW_UNSIGNED_WITH_WARNING)
                .build();
        try {
            ModelHandle handle = platform.models().load(ModelReference.of("e2e/test-model"));
            try {
                // warmup for JIT + caches
                for (int i = 0; i < 100; i++) {
                    handle.capability(io.cortexj.inference.TextGenerationCapability.class)
                            .generate(GenerationRequest.prompt("hi"));
                }
                final int calls = 1000;
                List<Long> nanos = new ArrayList<Long>(calls);
                for (int i = 0; i < calls; i++) {
                    long started = System.nanoTime();
                    handle.capability(io.cortexj.inference.TextGenerationCapability.class)
                            .generate(GenerationRequest.prompt("hi"));
                    nanos.add(Long.valueOf(System.nanoTime() - started));
                }
                Collections.sort(nanos);
                double p50ms = nanos.get(calls / 2).longValue() / 1_000_000.0;
                double p99ms = nanos.get((calls * 99) / 100).longValue() / 1_000_000.0;
                System.out.println("platform dispatch overhead: p50=" + p50ms
                        + " ms, p99=" + p99ms + " ms over " + calls + " calls");
                assertTrue(p99ms < 100.0d,
                        "p99 dispatch overhead must stay far below real inference cost, got "
                                + p99ms + " ms");
            } finally {
                handle.close();
            }
        } finally {
            platform.close();
        }
    }
}
