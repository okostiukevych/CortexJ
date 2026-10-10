package io.cortexj.nn;

import java.util.Arrays;

/**
 * Phase C sharded optimizer state (ADR-027): ZeRO-style partition of
 * AdamW moments across workers. Each worker owns its shard slice;
 * {@link #gather} reassembles the full moments (used by checkpoint
 * save). Parity against unsharded AdamW is asserted in the test.
 */
public final class ShardedMoments {

    private final int workers;
    private final double beta1;
    private final double beta2;
    private final double[][] first;
    private final double[][] second;
    private int size;

    public ShardedMoments(int parameters, int workers, double beta1, double beta2) {
        if (parameters < 1 || workers < 1) {
            throw new IllegalArgumentException("parameters and workers must be positive");
        }
        if (beta1 < 0 || beta1 >= 1 || beta2 < 0 || beta2 >= 1) {
            throw new IllegalArgumentException("betas must sit in [0, 1)");
        }
        this.workers = workers;
        this.beta1 = beta1;
        this.beta2 = beta2;
        this.first = new double[workers][];
        this.second = new double[workers][];
        this.size = parameters;
        int base = parameters / workers;
        for (int w = 0; w < workers; w++) {
            int from = w * base;
            int to = w == workers - 1 ? parameters : from + base;
            first[w] = new double[to - from];
            second[w] = new double[to - from];
        }
    }

    /**
     * Accumulates one gradient step into the owning shards (same math
     * as unsharded AdamW moments, partitioned).
     */
    public void accumulate(double[] gradients) {
        if (gradients.length != size) {
            throw new IllegalArgumentException("gradient shape mismatch");
        }
        for (int w = 0; w < workers; w++) {
            int from = shardFrom(w);
            for (int i = 0; i < first[w].length; i++) {
                double gradient = gradients[from + i];
                first[w][i] = beta1 * first[w][i] + (1 - beta1) * gradient;
                second[w][i] = beta2 * second[w][i] + (1 - beta2) * gradient * gradient;
            }
        }
    }

    /**
     * Gathers the full first/second moments in parameter order (used
     * by checkpoint save).
     */
    public double[][] gather() {
        double[][] full = new double[2][size];
        for (int w = 0; w < workers; w++) {
            int from = shardFrom(w);
            System.arraycopy(first[w], 0, full[0], from, first[w].length);
            System.arraycopy(second[w], 0, full[1], from, second[w].length);
        }
        return full;
    }

    public int workerShardSize(int worker) {
        return first[worker].length;
    }

    private int shardFrom(int worker) {
        int base = size / workers;
        int from = worker * base;
        return Math.min(from, size);
    }
}
