package io.cortexj.train.djl.models;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;

/** Shared decoder math: RMSNorm, SiLU, RoPE application, causal mask. */
final class LmOps {

    /** y = x * rsqrt(mean(x^2) + eps) * weight (broadcast over the last dim). */
    static NDArray rmsNorm(NDArray x, NDArray weight, float eps) {
        int last = x.getShape().dimension() - 1;
        NDArray inverse = x.square().mean(new int[]{last}, true).add(eps).pow(-0.5);
        return x.mul(inverse).mul(weight);
    }

    /**
     * x * sigmoid(x) with an overflow-free sigmoid: sigmoid(x) =
     * exp(x - max(x,0)) / (1 + exp(-|x|)), every exp argument is
     * non-positive. The naive 1/(1+exp(-x)) overflows to Inf for
     * x &lt;&lt; 0 and its backward then yields 0*Inf = NaN gradients
     * (observed live on Qwen3-0.6B: NaN LoRA grads from the first
     * step while the loss stays finite).
     */
    static NDArray silu(NDArray x) {
        NDArray shift = x.maximum(0);
        NDArray numerator = x.sub(shift).exp();
        NDArray abs = x.maximum(x.neg());
        NDArray denominator = abs.neg().exp().add(1);
        return x.mul(numerator.div(denominator));
    }

    /** cos[p][d] = cos(p * theta^(-2d/headDim)), headDim = 2 * half. */
    static float[] ropeCos(int positions, int half, double theta) {
        return ropeTable(positions, half, theta, true);
    }

    static float[] ropeSin(int positions, int half, double theta) {
        return ropeTable(positions, half, theta, false);
    }

    /**
     * Applies RoPE over the last dim split in halves; cos/sin are
     * (1, 1, seq, half) broadcast tables.
     */
    static NDArray applyRope(NDArray x, NDArray cos, NDArray sin, int headDim) {
        int half = headDim / 2;
        NDArray x1 = x.get("..., 0:" + half);
        NDArray x2 = x.get("..., " + half + ":");
        return x1.mul(cos).sub(x2.mul(sin)).concat(x1.mul(sin).add(x2.mul(cos)), -1);
    }

    /** Adds the additive causal mask (0 on/under the diagonal, -1e9 above). */

    /**
     * GQA head expansion (N, kvHeads, T, headDim) -> (N, kvHeads*times, T,
     * headDim) with INTERLEAVING ([k0,k0,k1,k1,...]): plain repeat tiles
     * the whole block ([k0,k1,k0,k1,...]) and misaligns every query group
     * past the first.
     */
    static NDArray repeatInterleaveHeads(NDArray x, int kvHeads, int times) {
        long n = x.getShape().get(0);
        long t = x.getShape().get(2);
        long headDim = x.getShape().get(3);
        return x.reshape(n, kvHeads, 1, t, headDim)
                .tile(new long[]{1, 1, times, 1, 1})
                .reshape(n, kvHeads * times, t, headDim);
    }    static NDArray causalScores(NDArray scores, NDManager manager, int positions) {
        float[] mask = new float[positions * positions];
        for (int i = 0; i < positions; i++) {
            for (int j = i + 1; j < positions; j++) {
                mask[i * positions + j] = -1e9f;
            }
        }
        // (1, T, T): broadcasts against the folded (batch * heads, T, T) scores
        return scores.add(manager.create(mask,
                new Shape(positions, positions)).reshape(1, positions, positions));
    }

    private static float[] ropeTable(int positions, int half, double theta, boolean cosine) {
        float[] table = new float[positions * half];
        for (int p = 0; p < positions; p++) {
            for (int d = 0; d < half; d++) {
                double angle = p * Math.pow(theta, -2.0 * d / (2.0 * half));
                table[p * half + d] = (float) (cosine ? Math.cos(angle) : Math.sin(angle));
            }
        }
        return table;
    }

    private LmOps() {
    }
}
