package io.cortexj.train.djl.models;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.training.ParameterStore;

/** SwiGLU MLP: down(silu(gate(x)) * up(x)); frozen or LoRA-wrapped projections. */
final class Mlp {

    private final ArchitectureConfig config;
    private NDArray gateWeight;
    private NDArray upWeight;
    private NDArray downWeight;
    private final LoraLinear gateLora;
    private final LoraLinear upLora;
    private final LoraLinear downLora;

    Mlp(ArchitectureConfig config, LoraLinear gateLora, LoraLinear upLora, LoraLinear downLora) {
        this.config = config;
        this.gateLora = gateLora;
        this.upLora = upLora;
        this.downLora = downLora;
    }

    void loadFrozen(float[] gate, float[] up, float[] down, NDManager manager) {
        int hidden = config.hiddenSize();
        int inter = config.intermediateSize();
        require(gate, inter * hidden, "gate_proj");
        require(up, inter * hidden, "up_proj");
        require(down, hidden * inter, "down_proj");
        gateWeight = manager.create(CausalGqaAttention.transpose(gate, inter, hidden),
                new Shape(hidden, inter));
        upWeight = manager.create(CausalGqaAttention.transpose(up, inter, hidden),
                new Shape(hidden, inter));
        downWeight = manager.create(CausalGqaAttention.transpose(down, hidden, inter),
                new Shape(inter, hidden));
    }

    private static void require(float[] values, int expected, String what) {
        if (values == null || values.length != expected) {
            throw new IllegalStateException("tensor " + what + " has "
                    + (values == null ? 0 : values.length) + " floats, expected "
                    + expected + " (architecture mismatch)");
        }
    }

    NDArray forward(NDArray x, ParameterStore store, boolean training) {
        NDArray gate = Projections.project(x, gateWeight, null, gateLora, store, training);
        NDArray up = Projections.project(x, upWeight, null, upLora, store, training);
        NDArray hidden = LmOps.silu(gate).mul(up);
        return Projections.project(hidden, downWeight, null, downLora, store, training);
    }
}
