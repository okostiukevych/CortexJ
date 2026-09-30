package io.cortexj.planner.internal;

import io.cortexj.error.PlanningException;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.model.ModelReference;
import io.cortexj.model.TaskCapability;
import io.cortexj.planner.ExecutionPlan;
import io.cortexj.planner.ExecutionRequest;
import io.cortexj.planner.PlanReason;
import io.cortexj.planner.ReasonCode;
import io.cortexj.planner.test.TestRepositoryProvider;
import io.cortexj.planner.test.TestRuntimeProvider;
import io.cortexj.runtime.DeviceType;
import io.cortexj.runtime.RuntimeDescriptor;
import io.cortexj.runtime.RuntimeId;
import io.cortexj.spi.runtime.RuntimeEvaluationRequest;
import io.cortexj.spi.runtime.RuntimeOpenRequest;
import io.cortexj.spi.runtime.RuntimeProbeContext;
import io.cortexj.spi.runtime.RuntimeProbeResult;
import io.cortexj.spi.runtime.RuntimeProvider;
import io.cortexj.spi.runtime.RuntimeSupport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Tag;

@Tag("concurrency")
class ProbeCacheTest {

    private static final ModelReference MODEL = ModelReference.of("Qwen/Qwen3-8B");
    private static final ModelArtifact GGUF =
            TestRuntimeProvider.artifact("qwen3-8b-q6_k.gguf", ArtifactFormat.GGUF, 6_600_000_000L, "q6_k");
    private static final ModelArtifact REMOTE_ARTIFACT =
            TestRuntimeProvider.artifact("qwen3-8b-remote", ArtifactFormat.REMOTE, 0L, null);

    static final class CountingProbeProvider implements RuntimeProvider {

        final AtomicInteger probeCount = new AtomicInteger();
        final AtomicReference<RuntimeProbeResult> current =
                new AtomicReference<RuntimeProbeResult>(RuntimeProbeResult.available("up"));
        volatile boolean throwOnProbe;
        private final RuntimeId id;
        private final ArtifactFormat format;
        private final DeviceType device;
        private final TaskCapability task;

        CountingProbeProvider(String id, ArtifactFormat format, DeviceType device, TaskCapability task) {
            this.id = RuntimeId.of(id);
            this.format = format;
            this.device = device;
            this.task = task;
        }

        @Override
        public RuntimeId id() {
            return id;
        }

        @Override
        public RuntimeDescriptor descriptor() {
            return RuntimeDescriptor.builder(id)
                    .version("1.0")
                    .format(format)
                    .device(device)
                    .task(task)
                    .maturity(RuntimeDescriptor.Maturity.STABLE)
                    .build();
        }

        @Override
        public RuntimeProbeResult probe(RuntimeProbeContext context) {
            probeCount.incrementAndGet();
            if (throwOnProbe) {
                throw new IllegalStateException("filesystem exploded");
            }
            return current.get();
        }

        @Override
        public RuntimeSupport evaluate(RuntimeEvaluationRequest request) {
            if (request.artifact() == null || request.artifact().format() != format) {
                return RuntimeSupport.unsupported(PlanReason.negative(ReasonCode.FORMAT_UNSUPPORTED,
                        "runtime " + id.value() + " does not support the artifact"));
            }
            return RuntimeSupport.full(device);
        }

        @Override
        public io.cortexj.spi.runtime.ModelRuntime open(RuntimeOpenRequest request) {
            throw new UnsupportedOperationException("test provider cannot open");
        }
    }

    private static DefaultExecutionPlanner planner(CountingProbeProvider provider, ModelArtifact... artifacts) {
        List<RuntimeProvider> runtimeProviders = new ArrayList<RuntimeProvider>();
        runtimeProviders.add(provider);
        return new DefaultExecutionPlanner(new PlannerInputs(
                runtimeProviders,
                Arrays.<io.cortexj.spi.repository.ModelRepositoryProvider>asList(new TestRepositoryProvider(artifacts)),
                new ArrayList<io.cortexj.spi.conversion.ModelConverter>(),
                new ArrayList<io.cortexj.spi.policy.ExecutionPolicy>(),
                null,
                TestRepositoryProvider.environment(25),
                TestRepositoryProvider.hardware(false),
                null,
                null,
                null,
                null,
                false,
                true,
                "0.1.0-test",
                0L));
    }

    @Test
    void concurrentPlansTriggerExactlyOneProbe() throws Exception {
        CountingProbeProvider provider =
                new CountingProbeProvider("llamacpp", ArtifactFormat.GGUF, DeviceType.CPU, TaskCapability.TEXT_GENERATION);
        final DefaultExecutionPlanner planner = planner(provider, GGUF);
        final CountDownLatch start = new CountDownLatch(1);
        final ExecutionPlan[] plans = new ExecutionPlan[4];
        final RuntimeException[] failures = new RuntimeException[4];
        Thread[] threads = new Thread[4];
        for (int i = 0; i < threads.length; i++) {
            final int index = i;
            threads[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        plans[index] = planner.plan(ExecutionRequest.forModel(MODEL));
                    } catch (RuntimeException e) {
                        failures[index] = e;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            threads[i].start();
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(30_000);
        }
        for (int i = 0; i < threads.length; i++) {
            if (failures[i] != null) {
                throw failures[i];
            }
            assertTrue(plans[i] != null, "plan " + i + " must complete");
        }
        assertEquals(1, provider.probeCount.get(),
                "concurrent plans must share a single probe per provider");
    }

    @Test
    void transientProbeFailureRecoversWithinNegativeTtl() {
        CountingProbeProvider provider =
                new CountingProbeProvider("remote-openai", ArtifactFormat.REMOTE, DeviceType.REMOTE, TaskCapability.TEXT_GENERATION);
        DefaultExecutionPlanner planner = planner(provider, REMOTE_ARTIFACT);
        planner.setProbeTtlsForTesting(60_000L, 100L);

        provider.current.set(RuntimeProbeResult.unavailable("network blip"));
        assertThrows(PlanningException.class, () -> planner.plan(ExecutionRequest.forModel(MODEL)),
                "unavailable probe must exclude the runtime from the plan");
        assertEquals(1, provider.probeCount.get(),
                "the negative result must be cached (no re-probe inside the TTL)");

        provider.current.set(RuntimeProbeResult.available("recovered"));
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ExecutionPlan recovered = planner.plan(ExecutionRequest.forModel(MODEL));
        assertEquals("remote-openai", recovered.selected().runtimeId(),
                "the runtime must be planned again once the negative TTL expired");
        assertEquals(2, provider.probeCount.get());
    }

    @Test
    void invalidateProbeCacheForcesImmediateReProbe() {
        CountingProbeProvider provider =
                new CountingProbeProvider("remote-openai", ArtifactFormat.REMOTE, DeviceType.REMOTE, TaskCapability.TEXT_GENERATION);
        DefaultExecutionPlanner planner = planner(provider, REMOTE_ARTIFACT);
        planner.setProbeTtlsForTesting(60_000L, 60_000L);

        provider.current.set(RuntimeProbeResult.unavailable("down"));
        assertThrows(PlanningException.class, () -> planner.plan(ExecutionRequest.forModel(MODEL)));

        provider.current.set(RuntimeProbeResult.available("back"));
        planner.invalidateProbeCache();
        ExecutionPlan recovered = planner.plan(ExecutionRequest.forModel(MODEL));
        assertEquals("remote-openai", recovered.selected().runtimeId(),
                "invalidateProbeCache() must force an immediate fresh probe");
        assertEquals(2, provider.probeCount.get());
    }

    @Test
    void probeThrowIsTreatedAsUnavailableWithNegativeTtl() {
        CountingProbeProvider provider =
                new CountingProbeProvider("llamacpp", ArtifactFormat.GGUF, DeviceType.CPU, TaskCapability.TEXT_GENERATION);
        DefaultExecutionPlanner planner = planner(provider, GGUF);
        planner.setProbeTtlsForTesting(60_000L, 100L);
        provider.throwOnProbe = true;
        assertThrows(PlanningException.class, () -> planner.plan(ExecutionRequest.forModel(MODEL)),
                "a probe that throws must not crash the planner");
        assertEquals(1, provider.probeCount.get(),
                "the probe-throw result must be cached within the negative TTL");
        provider.throwOnProbe = false;
        provider.current.set(RuntimeProbeResult.available("recovered"));
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ExecutionPlan recovered = planner.plan(ExecutionRequest.forModel(MODEL));
        assertEquals("llamacpp", recovered.selected().runtimeId(),
                "the runtime must recover after the negative TTL");
    }
}
