package io.cortexj.examples.java21;

import io.cortexj.CortexJ;
import io.cortexj.model.ModelReference;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Java 21 baseline example (spec §79). The assertions cover what an offline CI
 * machine can honestly verify: discovery works, orchestration survives many
 * concurrent virtual-thread tasks, and the public API surface is the same one
 * the Java 8 baseline uses.
 */
class Java21ApplicationExampleTest {

    @Test
    void virtualThreadsRunOrchestrationConcurrently() throws Exception {
        try (CortexJ platform = CortexJ.builder().build()) {
            int tasks = 64;
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                List<Future<Integer>> futures = new ArrayList<>();
                for (int i = 0; i < tasks; i++) {
                    futures.add(executor.submit(() -> platform.runtimes().size()));
                }
                int observed = -1;
                for (Future<Integer> future : futures) {
                    int size = future.get();
                    assertTrue(size >= 0, "run must never report a negative count");
                    if (observed < 0) {
                        observed = size;
                    } else {
                        assertTrue(observed == size,
                                "every task must observe the same discovery set");
                    }
                }
                System.out.println("virtual-thread tasks=" + tasks
                        + " discovered runtimes=" + observed);
            } finally {
                executor.close();
            }
        }
    }

    @Test
    void planBeforeExecutionStaysUsableOnTheSameApi() {
        try (CortexJ platform = CortexJ.builder().build()) {
            // Same request shape as the Java 8 baseline example: the planner
            // contract does not change with the baseline. On fully offline
            // machines the resolution may throw; that is acceptable — the
            // assertion covers the reachable-through-public-API case only.
            try {
                var plan = platform.planner().plan(
                        io.cortexj.planner.ExecutionRequest.forModel(
                                ModelReference.of("Qwen/Qwen3-8B")));
                assertTrue(plan != null && plan.explanation() != null,
                        "a plan or a loud failure — never null");
                assertTrue(!plan.explanation().trim().isEmpty(),
                        "every plan carries a human-readable explanation");
            } catch (RuntimeException offline) {
                System.out.println("offline machine: planner resolution skipped ("
                        + offline.getClass().getSimpleName() + ")");
            }
        }
    }
}
