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

    /** x * sigmoid(x) = x / (1 + e^-x), built from verified primitives. */
    static NDArray silu(NDArray x) {
        return x.mul(x.neg().exp().add(1).pow(-1));
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
    static NDArray causalScores(NDArray scores, NDManager manager, int positions) {
        float[] mask = new float[positions * positions];
        for (int i = 0; i < positions; i++) {
            for (int j = i + 1; j < positions; j++) {
                mask[i * positions + j] = -1e9f;
            }
        }
        return scores.add(manager.create(mask,
                new Shape(positions, positions)).reshape(1, 1, positions, positions));
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
