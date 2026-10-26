package io.cortexj.planner.internal;

import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.model.ModelReference;
import io.cortexj.model.TaskCapability;
import io.cortexj.planner.test.TestRepositoryProvider;
import io.cortexj.planner.test.TestRuntimeProvider;
import io.cortexj.runtime.DeviceType;
import io.cortexj.runtime.RuntimeId;
import io.cortexj.spi.runtime.RuntimeProbeContext;
import io.cortexj.spi.runtime.RuntimeProbeResult;
import io.cortexj.spi.runtime.RuntimeProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Audit 2026-10-08 (#153): repeated probe failures must back the negative
 * TTL off instead of re-probing a dead runtime on every plan.
 */
class CircuitBreakerTest {

    static final class AlwaysDownProvider implements RuntimeProvider {

        final AtomicInteger probeCount = new AtomicInteger();
        private final RuntimeId id = RuntimeId.of("always-down");

        @Override
        public RuntimeId id() {
            return id;
        }

        @Override
        public io.cortexj.runtime.RuntimeDescriptor descriptor() {
            return io.cortexj.runtime.RuntimeDescriptor.builder(id)
                    .version("1.0")
                    .format(ArtifactFormat.GGUF)
                    .device(DeviceType.CPU)
                    .task(TaskCapability.TEXT_GENERATION)
                    .maturity(io.cortexj.runtime.RuntimeDescriptor.Maturity.STABLE)
                    .build();
        }

        @Override
        public RuntimeProbeResult probe(RuntimeProbeContext context) {
            probeCount.incrementAndGet();
            return RuntimeProbeResult.unavailable("down");
        }

        @Override
        public io.cortexj.spi.runtime.RuntimeSupport evaluate(
                io.cortexj.spi.runtime.RuntimeEvaluationRequest request) {
            return io.cortexj.spi.runtime.RuntimeSupport.full(DeviceType.CPU);
        }

        @Override
        public io.cortexj.spi.runtime.ModelRuntime open(
                io.cortexj.spi.runtime.RuntimeOpenRequest request) {
            throw new UnsupportedOperationException("test provider cannot open");
        }
    }

    private static DefaultExecutionPlanner planner(RuntimeProvider provider) {
        ModelArtifact gguf = TestRuntimeProvider.artifact(
                "qwen3-8b-q6_k.gguf", ArtifactFormat.GGUF, 6_600_000_000L, "q6_k");
        List<RuntimeProvider> runtimeProviders = new ArrayList<RuntimeProvider>();
        runtimeProviders.add(provider);
        return new DefaultExecutionPlanner(new PlannerInputs(
                runtimeProviders,
                Arrays.<io.cortexj.spi.repository.ModelRepositoryProvider>asList(
                        new TestRepositoryProvider(gguf)),
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
    void repeatedFailuresBackOffPhysicalProbes() {
        AlwaysDownProvider provider = new AlwaysDownProvider();
        DefaultExecutionPlanner planner = planner(provider);
        for (int i = 0; i < 5; i++) {
            assertFalse(planner.probeShared(provider).isUsable());
            // explicit invalidation forces a physical re-probe (this is what
            // runtimeHealth() does); without it the negative TTL would shield
            planner.invalidateProbeCache();
        }
        assertEquals(5, provider.probeCount.get());
        // the 6th plan hits the open circuit: no physical probe even though
        // the cache was just invalidated
        planner.invalidateProbeCache();
        assertFalse(planner.probeShared(provider).isUsable());
        assertEquals(5, provider.probeCount.get(),
                "an open circuit must hold through explicit invalidation");
    }

    @Test
    void circuitHalfOpensAfterCooldown() throws Exception {
        System.setProperty("cortexj.planner.circuitCooldownMillis", "150");
        try {
            AlwaysDownProvider provider = new AlwaysDownProvider();
            DefaultExecutionPlanner planner = planner(provider);
            for (int i = 0; i < 5; i++) {
                assertFalse(planner.probeShared(provider).isUsable());
                planner.invalidateProbeCache();
            }
            assertFalse(planner.probeShared(provider).isUsable());
            assertEquals(5, provider.probeCount.get());
            Thread.sleep(250L);
            planner.invalidateProbeCache();
            assertFalse(planner.probeShared(provider).isUsable());
            assertEquals(6, provider.probeCount.get(),
                    "after the cooldown one trial probe must go through");
            // Immediately after the failed half-open trial probe, the circuit must re-open
            planner.invalidateProbeCache();
            assertFalse(planner.probeShared(provider).isUsable());
            assertEquals(6, provider.probeCount.get(),
                    "a single failed half-open probe must immediately re-open the circuit");
        } finally {
            System.clearProperty("cortexj.planner.circuitCooldownMillis");
        }
    }
}
