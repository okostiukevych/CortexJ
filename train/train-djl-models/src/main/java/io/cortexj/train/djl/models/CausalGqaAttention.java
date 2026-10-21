package io.cortexj.train.djl.models;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.training.ParameterStore;

/**
 * Causal GQA attention with RoPE and optional QK-norm (QWEN3). Frozen
 * weights are (in, out) NDArrays; LoRA-wrapped projections route
 * through the wrapper (which owns the frozen weight).
 */
final class CausalGqaAttention {

    private final ArchitectureConfig config;
    private final int layerIndex;
    private NDArray wq;
    private NDArray wk;
    private NDArray wv;
    private NDArray wo;
    private NDArray bq;
    private NDArray bk;
    private NDArray bv;
    private NDArray qNorm;
    private NDArray kNorm;
    private final LoraLinear qLora;
    private final LoraLinear kLora;
    private final LoraLinear vLora;
    private final LoraLinear oLora;

    CausalGqaAttention(ArchitectureConfig config, int layerIndex, LoraLinear qLora,
                       LoraLinear kLora, LoraLinear vLora, LoraLinear oLora) {
        this.config = config;
        this.layerIndex = layerIndex;
        this.qLora = qLora;
        this.kLora = kLora;
        this.vLora = vLora;
        this.oLora = oLora;
    }

    void loadFrozen(float[] q, float[] k, float[] v, float[] o,
                    float[] qNormWeight, float[] kNormWeight, NDManager manager) {
        int hidden = config.hiddenSize();
        int qDim = config.numHeads() * config.headDim();
        int kvDim = config.kvDim();
        require(q, qDim * hidden, "q_proj");
        require(k, kvDim * hidden, "k_proj");
        require(v, kvDim * hidden, "v_proj");
        require(o, hidden * qDim, "o_proj");
        wq = manager.create(transpose(q, qDim, hidden), new Shape(hidden, qDim));
        wk = manager.create(transpose(k, kvDim, hidden), new Shape(hidden, kvDim));
        wv = manager.create(transpose(v, kvDim, hidden), new Shape(hidden, kvDim));
        wo = manager.create(transpose(o, hidden, qDim), new Shape(qDim, hidden));
        if (config.qkNorm()) {
            require(qNormWeight, config.headDim(), "q_norm");
            require(kNormWeight, config.kvHeadDim(), "k_norm");
            qNorm = manager.create(qNormWeight, new Shape(config.headDim()));
            kNorm = manager.create(kNormWeight, new Shape(config.kvHeadDim()));
        }
    }

    void loadBiases(float[] q, float[] k, float[] v, NDManager manager) {
        require(q, config.hiddenSize(), "q_proj.bias");
        require(k, config.kvDim(), "k_proj.bias");
        require(v, config.kvDim(), "v_proj.bias");
        bq = manager.create(q, new Shape(config.hiddenSize()));
        bk = manager.create(k, new Shape(config.kvDim()));
        bv = manager.create(v, new Shape(config.kvDim()));
    }

    NDArray forward(NDArray x, NDArray cos, NDArray sin, NDManager manager,
                    ParameterStore store, boolean training) {
        int n = (int) x.getShape().get(0);
        int t = (int) x.getShape().get(1);
        int heads = config.numHeads();
        int kvHeads = config.numKvHeads();
        int headDim = config.headDim();
        int kvHeadDim = config.kvHeadDim();

        NDArray q = Projections.project(x, wq, bq, qLora, store, training);
        NDArray k = Projections.project(x, wk, bk, kLora, store, training);
        NDArray v = Projections.project(x, wv, bv, vLora, store, training);

        q = q.reshape(n, t, heads, headDim).transpose(0, 2, 1, 3);
        k = k.reshape(n, t, kvHeads, kvHeadDim).transpose(0, 2, 1, 3);
        v = v.reshape(n, t, kvHeads, kvHeadDim).transpose(0, 2, 1, 3);

        if (config.qkNorm()) {
            q = LmOps.rmsNorm(q, qNorm, (float) config.rmsEps());
            k = LmOps.rmsNorm(k, kNorm, (float) config.rmsEps());
        }

        NDArray cosT = cos.get("0:" + t).reshape(1, 1, t, headDim / 2);
        NDArray sinT = sin.get("0:" + t).reshape(1, 1, t, headDim / 2);
        q = LmOps.applyRope(q, cosT, sinT, headDim);
        k = LmOps.applyRope(k, cosT, sinT, kvHeadDim);

        if (kvHeads != heads) {
            int times = heads / kvHeads;
            k = LmOps.repeatInterleaveHeads(k, kvHeads, times);
            v = LmOps.repeatInterleaveHeads(v, kvHeads, times);
        }

        // fold the heads into the batch: the engine batchMatMul is 3-D only
        NDArray foldedQ = q.reshape(n * heads, t, headDim);
        NDArray foldedK = k.reshape(n * heads, t, kvHeadDim);
        NDArray foldedV = v.reshape(n * heads, t, kvHeadDim);
        NDArray scores = foldedQ.batchMatMul(foldedK.transpose(0, 2, 1))
                .mul(1.0 / Math.sqrt(headDim));
        scores = LmOps.causalScores(scores, manager, t);
        int last = scores.getShape().dimension() - 1;
        NDArray folded = scores.softmax(last).batchMatMul(foldedV);
        NDArray context = folded.reshape(n, heads, t, headDim);
        int concatDim = heads * headDim;
        context = context.transpose(0, 2, 1, 3).reshape(n, t, concatDim);
        return Projections.project(context, wo, null, oLora, store, training);
    }

    static float[] transpose(float[] outIn, int out, int in) {
        float[] t = new float[in * out];
        for (int o = 0; o < out; o++) {
            for (int i = 0; i < in; i++) {
                t[i * out + o] = outIn[o * in + i];
            }
        }
        return t;
    }

    private static void require(float[] values, int expected, String what) {
        if (values == null || values.length != expected) {
            throw new IllegalStateException("tensor " + what + " has "
                    + (values == null ? 0 : values.length) + " floats, expected "
                    + expected + " (architecture mismatch)");
        }
    }
}
