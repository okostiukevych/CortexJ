package io.cortexj.train.distributed;

/**
 * Phase A (ADR-027): single-node data-parallel training. The batch is
 * split across N in-process workers; per-shard gradients are averaged
 * (sum / workerCount). Real cross-process collectives and fault
 * semantics are Phase B/C.
 *
 * <p>The reference loss is {@code mean((w·x)^2)} over the batch; its
 * analytic gradient is {@code 2·(w·x)·x / rows}. Parity contract: the
 * sharded average must equal the single-worker full-batch gradient.</p>
 */
public class DataParallel {

    /**
     * Averages the per-shard gradients of the reference loss.
     */
    public double[] gradients(double[] weights, double[] batch, int workers) {
        requireValid(weights, batch, workers);
        int rows = Math.max(1, batch.length / workers);
        double[] averaged = new double[weights.length];
        for (int worker = 0; worker < workers; worker++) {
            int from = Math.min(batch.length, worker * rows);
            int to = worker == workers - 1 ? batch.length : Math.min(batch.length, from + rows);
            int sliceLength = to - from;
            if (sliceLength <= 0) {
                continue;
            }
            double weight = (double) sliceLength / batch.length;
            double[] shardGradient = shardGradient(weights, batch, from, to);
            for (int p = 0; p < weights.length; p++) {
                averaged[p] += shardGradient[p] * weight;
            }
        }
        return averaged;
    }

    /**
     * The mean reference loss over the full batch, computed shard-wise.
     */
    public double loss(double[] weights, double[] batch, int workers) {
        requireValid(weights, batch, workers);
        int rows = Math.max(1, batch.length / workers);
        double total = 0;
        for (int worker = 0; worker < workers; worker++) {
            int from = Math.min(batch.length, worker * rows);
            int to = worker == workers - 1 ? batch.length : Math.min(batch.length, from + rows);
            int sliceLength = to - from;
            if (sliceLength <= 0) {
                continue;
            }
            double weight = (double) sliceLength / batch.length;
            total += shardLoss(weights, batch, from, to) * weight;
        }
        return total;
    }

    private void requireValid(double[] weights, double[] batch, int workers) {
        if (weights == null || weights.length == 0) {
            throw new IllegalArgumentException("weights must not be empty");
        }
        if (batch == null || batch.length == 0) {
            throw new IllegalArgumentException("batch must not be empty");
        }
        if (workers < 1) {
            throw new IllegalArgumentException("worker count must be at least 1");
        }
    }

    private double[] shardGradient(double[] weights, double[] batch, int from, int to) {
        double rows = to - from;
        double[] gradient = new double[weights.length];
        for (int r = from; r < to; r++) {
            double x = batch[r];
            double prediction = prediction(weights, x);
            for (int p = 0; p < weights.length; p++) {
                gradient[p] += 2 * prediction * x / rows;
            }
        }
        return gradient;
    }

    private double shardLoss(double[] weights, double[] batch, int from, int to) {
        double rows = to - from;
        double total = 0;
        for (int r = from; r < to; r++) {
            double prediction = prediction(weights, batch[r]);
            total += prediction * prediction / rows;
        }
        return total;
    }

    private double prediction(double[] weights, double x) {
        double prediction = 0;
        for (double weight : weights) {
            prediction += weight * x;
        }
        return prediction;
    }
}
