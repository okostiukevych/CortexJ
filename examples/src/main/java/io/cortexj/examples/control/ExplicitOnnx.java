package io.cortexj.examples.control;

import io.cortexj.CortexJ;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelReference;
import io.cortexj.planner.ExecutionRequest;

/**
 * Explicit ONNX requirement.
 *
 * <p>When the ONNX path is impossible the platform returns an actionable
 * {@code PlanningException}; it never silently falls back to another runtime.</p>
 */
public final class ExplicitOnnx {

    public static void main(String[] args) {
        try (CortexJ platform = CortexJ.builder().build()) {
            ExecutionRequest request = ExecutionRequest.builder(ModelReference.of("company/model"))
                    .requiredRuntime("onnx")
                    .build();
            try (ModelHandle model = platform.models().load(request)) {
                System.out.println(model.generate(
                        io.cortexj.inference.GenerationRequest.prompt("hi")).text());
            } catch (io.cortexj.error.PlanningException impossible) {
                // honest typed failure with rejected candidates and remediation
                System.err.println(impossible);
            }
        }
    }

    private ExplicitOnnx() {
    }
}
