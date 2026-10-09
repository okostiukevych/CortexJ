package io.cortexj.train.standard;

import io.cortexj.train.LossFunction;

/**
 * Cross-entropy over logits with a softmax normalization inside (the
 * gradient is softmax - target). Hand-computed values in the test.
 */
public final class CrossEntropy implements LossFunction {

    public static final double EPSILON = 1e-12;

    @Override
    public String id() {
        return "cross-entropy";
    }

    @Override
    public double loss(double[] logits, double[] target) {
        double max = Double.NEGATIVE_INFINITY;
        for (double logit : logits) {
            max = Math.max(max, logit);
        }
        double total = 0;
        for (double logit : logits) {
            total += Math.exp(logit - max);
        }
        double loss = 0;
        for (int i = 0; i < logits.length; i++) {
            if (target[i] > 0) {
                loss -= target[i] * Math.log(Math.exp(logits[i] - max) / total + EPSILON);
            }
        }
        return loss;
    }

    @Override
    public double[] gradient(double[] logits, double[] target) {
        double max = Double.NEGATIVE_INFINITY;
        for (double logit : logits) {
            max = Math.max(max, logit);
        }
        double total = 0;
        for (double logit : logits) {
            total += Math.exp(logit - max);
        }
        double[] gradient = new double[logits.length];
        for (int i = 0; i < logits.length; i++) {
            double softmax = Math.exp(logits[i] - max) / total;
            gradient[i] = softmax - target[i];
        }
        return gradient;
    }
}
