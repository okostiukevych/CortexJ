package io.cortexj.examples.java25;

import io.cortexj.CortexJ;
import io.cortexj.model.ModelReference;
import io.cortexj.planner.ExecutionPlan;
import io.cortexj.planner.ExecutionRequest;

/**
 * Java 25 application with the optional JInfer engine (spec §80).
 *
 * <p>The business code is identical to the plan-before-execution example of the
 * Java 8 baseline — no import names any engine. The optional
 * <code>cortexj-runtime-jinfer</code> dependency only adds a candidate the
 * planner can see: JInfer registers itself through the same
 * {@code RuntimeProvider} ServiceLoader route every other runtime uses.
 *
 * <p>Remove the JInfer dependency and this application's logic, requests and
 * planner calls stay <strong>byte-identical</strong> — llama.cpp/ONNX remain
 * available options. This module builds on Java 25+ only (JInfer's own floor).
 */
public final class JinferCandidateExample {

    public static void main(String[] args) {
        try (CortexJ platform = CortexJ.builder().build()) {
            ExecutionPlan plan = platform.planner().plan(
                    ExecutionRequest.forModel(ModelReference.of("Qwen/Qwen3-8B")));
            System.out.println(plan.explanation());
            boolean jinferVisible = platform.runtimes().stream()
                    .anyMatch(d -> "jinfer".equals(d.id().value()));
            System.out.println("jinfer discovered: " + jinferVisible);
            System.out.println("JAVA25-JINFER-EXAMPLE-OK");
        }
    }

    private JinferCandidateExample() {
    }
}
