package io.cortexj.planner.test;

import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.model.ModelReference;
import io.cortexj.model.TaskCapability;
import io.cortexj.model.TrustPolicy;
import io.cortexj.planner.ExecutionPlan;
import io.cortexj.planner.ExecutionRequest;
import io.cortexj.planner.ReasonCode;
import io.cortexj.planner.internal.DefaultExecutionPlanner;
import io.cortexj.planner.internal.PlannerInputs;
import io.cortexj.runtime.DeviceType;
import io.cortexj.spi.repository.ModelRepositoryProvider;
import io.cortexj.spi.repository.RepositoryId;
import io.cortexj.spi.repository.ResolvedArtifact;
import io.cortexj.spi.repository.ResolvedModel;
import io.cortexj.spi.repository.ResolutionContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit 2026-10-08 (#153): TRUSTED_ONLY refusals must be visible at plan
 * time, not just as load-time failures.
 */
class TrustWarningTest {

    private static final ModelArtifact GGUF =
            TestRuntimeProvider.artifact("qwen3-8b-q6_k.gguf", ArtifactFormat.GGUF, 6_600_000_000L, "q6_k");

    static final class NotedRepository implements ModelRepositoryProvider {

        private final RepositoryId id = RepositoryId.of("noted-repo");

        @Override
        public RepositoryId id() {
            return id;
        }

        @Override
        public boolean supports(ModelReference reference) {
            return true;
        }

        @Override
        public ResolvedModel resolve(ModelReference reference, ResolutionContext context) {
            ModelDescriptor descriptor = ModelDescriptor.builder(reference.value())
                    .revision("testrev")
                    .task(TaskCapability.TEXT_GENERATION)
                    .trustNote("community upload without verified provenance")
                    .artifact(GGUF)
                    .build();
            List<ResolvedArtifact> artifacts = new ArrayList<ResolvedArtifact>();
            artifacts.add(TestRepositoryProvider.remoteArtifact(GGUF));
            return new ResolvedModel(descriptor, "testrev", id, artifacts);
        }
    }

    private static DefaultExecutionPlanner planner(TrustPolicy policy) {
        List<io.cortexj.spi.runtime.RuntimeProvider> runtimeProviders =
                new ArrayList<io.cortexj.spi.runtime.RuntimeProvider>();
        runtimeProviders.add(new TestRuntimeProvider("llamacpp", ArtifactFormat.GGUF)
                .devices(DeviceType.CPU)
                .task(TaskCapability.TEXT_GENERATION)
                .supportsEverything(true)
                .maturity(io.cortexj.runtime.RuntimeDescriptor.Maturity.STABLE));
        return new DefaultExecutionPlanner(new PlannerInputs(
                runtimeProviders,
                Arrays.<ModelRepositoryProvider>asList(new NotedRepository()),
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
                0L,
                java.util.Collections.<String, String>emptyMap(),
                io.cortexj.planner.benchmark.BenchmarkHints.empty(),
                policy));
    }

    @Test
    void trustedOnlyWarnsAboutComingRefusal() {
        ExecutionPlan plan = planner(TrustPolicy.TRUSTED_ONLY)
                .plan(ExecutionRequest.forModel(ModelReference.of("Qwen/Qwen3-8B")));
        boolean warned = false;
        for (io.cortexj.planner.PlanReason reason : plan.warnings()) {
            if (reason.code() == ReasonCode.TRUST_POLICY_BLOCKED) {
                warned = true;
            }
        }
        assertTrue(warned, "TRUSTED_ONLY with an untrusted model must warn at plan time");
    }

    @Test
    void allowUnsignedStaysSilent() {
        ExecutionPlan plan = planner(TrustPolicy.ALLOW_UNSIGNED_WITH_WARNING)
                .plan(ExecutionRequest.forModel(ModelReference.of("Qwen/Qwen3-8B")));
        for (io.cortexj.planner.PlanReason reason : plan.warnings()) {
            assertTrue(reason.code() != ReasonCode.TRUST_POLICY_BLOCKED,
                    "ALLOW_UNSIGNED must not raise trust warnings");
        }
    }
}
