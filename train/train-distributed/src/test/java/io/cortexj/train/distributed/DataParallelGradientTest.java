package io.cortexj.train.distributed;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase A (ADR-027): single-node data-parallel gradients. Per-shard
 * gradients averaged (sum / workerCount) must equal the equivalent
 * single-worker full-batch gradient.
 */
class DataParallelGradientTest {

    @Test
    void shardedGradientAverageEqualsFullBatch() {
        // loss = mean over the full batch of (w * x)^2
        double[] weights = {0.5, -1.0};
        double[] batch = {1.0, 2.0, -0.5, 3.0};
        int shards = 2;

        double[] averaged = new DataParallel().gradients(weights, batch, shards);
        double[] fullBatch = new DataParallel().gradients(weights, batch, 1);
        for (int i = 0; i < fullBatch.length; i++) {
            assertEquals(fullBatch[i], averaged[i], 1e-9,
                    "sharded average must equal full-batch gradient at " + i);
        }

        // Uneven batch size (5 elements across 2 workers -> shards of 2 and 3)
        double[] unevenBatch = {1.0, 2.0, -0.5, 3.0, 1.5};
        double[] unevenSharded = new DataParallel().gradients(weights, unevenBatch, 2);
        double[] unevenFull = new DataParallel().gradients(weights, unevenBatch, 1);
        for (int i = 0; i < unevenFull.length; i++) {
            assertEquals(unevenFull[i], unevenSharded[i], 1e-9);
        }
        assertEquals(new DataParallel().loss(weights, unevenBatch, 1),
                new DataParallel().loss(weights, unevenBatch, 2), 1e-9);
    }

    @Test
    void lossIsFiniteAndSplitIsLossless() {
        double[] weights = {1.0, 1.0};
        double[] batch = {1.0, 1.0};
        double loss = new DataParallel().loss(weights, batch, 2);
        // w = [1,1], x = 1: prediction = 2, mean squared = 4
        assertEquals(4.0, loss, 1e-9);
    }
}
