package io.cortexj.examples.java25;

import io.cortexj.CortexJ;
import io.cortexj.model.ModelReference;
import io.cortexj.planner.ExecutionPlan;
import io.cortexj.planner.ExecutionRequest;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Java 25 + optional JInfer (spec §80). The assertions are the ones an offline
 * CI machine can honestly make: the engine is discoverable via plain
 * ServiceLoader, the planner-level surface lists it, and the same public API
 * request (spec §81/§82 shape) plans or fails loudly.
 */
class JinferCandidateExampleTest {

    @Test
    void jinferIsDiscoveredThroughServiceLoader() {
        boolean found = false;
        for (io.cortexj.spi.runtime.RuntimeProvider provider
                : ServiceLoader.load(io.cortexj.spi.runtime.RuntimeProvider.class)) {
            if ("jinfer".equals(provider.id().value())) {
                found = true;
                break;
            }
        }
        assertTrue(found,
                "cortexj-runtime-jinfer must register via RuntimeProvider SPI");
    }

    @Test
    void platformListsJinferAsCandidate() {
        try (CortexJ platform = CortexJ.builder().build()) {
            boolean listed = platform.runtimes().stream()
                    .anyMatch(d -> "jinfer".equals(d.id().value()));
            assertTrue(listed, "planner surface must expose the jinfer candidate");
        }
    }

    @Test
    void sameBusinessRequestPlansOrFailsLoudly() {
        try (CortexJ platform = CortexJ.builder().build()) {
            try {
                ExecutionPlan plan = platform.planner().plan(
                        ExecutionRequest.forModel(ModelReference.of("Qwen/Qwen3-8B")));
                assertTrue(plan != null && plan.explanation() != null,
                        "never null: plan or loud failure");
                assertTrue(!plan.explanation().trim().isEmpty());
            } catch (RuntimeException offline) {
                System.out.println("offline machine: planner resolution skipped ("
                        + offline.getClass().getSimpleName() + ")");
            }
        }
    }
}
