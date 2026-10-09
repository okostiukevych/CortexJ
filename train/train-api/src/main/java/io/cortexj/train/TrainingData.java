package io.cortexj.train;

import java.util.Arrays;

/**
 * Training data hand-off for the live backend path: rows of a tiny
 * linear model (x/y pairs). The dataset fingerprint stays in the
 * request for provenance; the data itself is optional and used by
 * backends that execute training for real.
 */
public final class TrainingData {

    private final double[] inputs;
    private final double[] targets;
    private final int featureCount;

    private TrainingData(double[] inputs, double[] targets, int featureCount) {
        this.inputs = inputs.clone();
        this.targets = targets.clone();
        this.featureCount = featureCount;
    }

    /**
     * Single-feature linear-model data: {@code inputs[i]} maps to
     * {@code targets[i]}.
     */
    public static TrainingData of(double[] inputs, double[] targets) {
        if (inputs == null || targets == null || inputs.length != targets.length
                || inputs.length == 0) {
            throw new IllegalArgumentException("inputs and targets must be parallel and non-empty");
        }
        return new TrainingData(inputs, targets, 1);
    }

    public int rows() {
        return targets.length;
    }

    public int featureCount() {
        return featureCount;
    }

    public double inputAt(int row) {
        return inputs[row];
    }

    public double targetAt(int row) {
        return targets[row];
    }

    public double[] targets() {
        return targets.clone();
    }

    @Override
    public String toString() {
        return "TrainingData rows=" + rows() + " features=" + featureCount
                + " first=" + (rows() > 0 ? Arrays.toString(new double[]{inputs[0], targets[0]}) : "");
    }
}
