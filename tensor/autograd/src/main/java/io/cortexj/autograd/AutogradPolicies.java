package io.cortexj.autograd;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Autograd extension policies (ADR-026): gradient hooks, graph
 * retention, checkpointing/recomputation and release of intermediates.
 * Split from the core tape to keep the graph model focused.
 */
public final class AutogradPolicies {

    private AutogradPolicies() {
    }

    /**
     * Gradient checkpointing over one segment (ADR-026): the forward
     * runs without recording; on backward the segment recomputes (with
     * the same input variables — the body must close over them), the
     * recomputed subgraph's backward runs with the upstream gradient,
     * and the recomputed intermediates are released.
     */
    public static Variable checkpoint(Autograd autograd, Supplier<Variable> body) {
        Variable output = autograd.noGrad(body);
        Variable tracked = new Variable(output.data(), true);
        autograd.record(tracked, List.of(), gradOutput -> {
            int beforeRecompute = autograd.tapeSize();
            Variable recomputed = body.get();
            int afterRecompute = autograd.tapeSize();
            if (afterRecompute > beforeRecompute) {
                recomputed.seedGradient(gradOutput);
                List<TapeNode> segment = new ArrayList<>(
                        autograd.tapeNodes().subList(beforeRecompute, afterRecompute));
                Collections.reverse(segment);
                for (TapeNode node : segment) {
                    node.applyBackward(node.output().gradInternal());
                }
                // release the recomputed intermediates
                autograd.tapeNodes().subList(beforeRecompute, afterRecompute).clear();
            }
            return new double[0][0];
        });
        return tracked;
    }
}
