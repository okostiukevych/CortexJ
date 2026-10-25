package io.cortexj.examples.java21;

import io.cortexj.CortexJ;
import io.cortexj.model.ModelReference;
import io.cortexj.planner.ExecutionPlan;
import io.cortexj.planner.ExecutionRequest;
import io.cortexj.runtime.RuntimeDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Java 21 application example (spec §79).
 *
 * <p>The business code is byte-for-byte the same as the Java 8 baseline example
 * (see {@code io.cortexj.examples.planning.PlanBeforeExecution}): reference a
 * model, plan before execution, read the explanation. Only the starter
 * dependency changed ({@code cortexj-starter-java21}); the runtime selection,
 * planner contract and public API are identical.
 *
 * <p>The Java 21-specific piece is optional platform-side optimization of the
 * demo: the concurrent orchestration tasks below run on virtual threads — the
 * JVM handles thousands of in-flight planning/inspection requests without
 * business code knowing about threading. The example runs on Java 21+ only.
 */
public final class Java21ApplicationExample {

    public static void main(String[] args) throws Exception {
        try (CortexJ platform = CortexJ.builder().build()) {
            planBeforeExecution(platform);
            runOrchestrationOnVirtualThreads(platform);
            System.out.println("JAVA21-EXAMPLE-OK");
        }
    }

    /** Spec §82 scenario, unchanged across baselines. */
    private static void planBeforeExecution(CortexJ platform) {
        ExecutionPlan plan = platform.planner().plan(
                ExecutionRequest.forModel(ModelReference.of("Qwen/Qwen3-8B")));
        System.out.println(plan.explanation());
    }

    /**
     * Virtual threads carry the orchestration work. Each task only touches the
     * public API surface — no thread management in business code.
     */
    private static void runOrchestrationOnVirtualThreads(CortexJ platform) throws Exception {
        int tasks = 64;
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < tasks; i++) {
                futures.add(executor.submit(
                        () -> platform.runtimes().size()));
            }
            int totalCounts = 0;
            for (Future<Integer> future : futures) {
                totalCounts += future.get();
            }
            System.out.println("virtual-thread orchestration: " + tasks
                    + " tasks, aggregate registered runtimes = " + totalCounts);
        } finally {
            executor.close();
        }
    }

    private Java21ApplicationExample() {
    }
}
