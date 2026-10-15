package io.cortexj.train.djl.models;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.training.ParameterStore;

/** Pre-norm residual layer: x + attn(norm(x)); x + mlp(norm(x)). */
final class DecoderLayer {

    final CausalGqaAttention attention;
    final Mlp mlp;
    private final ArchitectureConfig config;
    private ai.djl.ndarray.NDArray inputNorm;
    private ai.djl.ndarray.NDArray postNorm;

    DecoderLayer(ArchitectureConfig config, CausalGqaAttention attention, Mlp mlp) {
        this.config = config;
        this.attention = attention;
        this.mlp = mlp;
    }

    void loadFrozen(float[] inputNormWeight, float[] postNormWeight, NDManager manager) {
        inputNorm = manager.create(inputNormWeight, new Shape(config.hiddenSize()));
        postNorm = manager.create(postNormWeight, new Shape(config.hiddenSize()));
    }

    NDArray forward(NDArray x, NDArray cos, NDArray sin, NDManager manager,
                    ParameterStore store, boolean training) {
        x = x.add(attention.forward(
                LmOps.rmsNorm(x, inputNorm, (float) config.rmsEps()),
                cos, sin, manager, store, training));
        x = x.add(mlp.forward(
                LmOps.rmsNorm(x, postNorm, (float) config.rmsEps()), store, training));
        return x;
    }
}
