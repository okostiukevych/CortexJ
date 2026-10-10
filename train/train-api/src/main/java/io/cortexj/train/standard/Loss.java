package io.cortexj.train.standard;

import io.cortexj.train.LossFunction;

/**
 * Loss factories: the standard objectives by name. Each factory is pure
 * math, hand-computed in the tests.
 */
public final class Loss {

    private Loss() {
    }

    public static LossFunction crossEntropy() {
        return new CrossEntropy();
    }

    public static LossFunction meanSquaredError() {
        return new LossFunction() {
            @Override
            public String id() {
                return "mean-squared-error";
            }

            @Override
            public double loss(double[] prediction, double[] target) {
                requireSameLength(prediction, target);
                double total = 0;
                for (int i = 0; i < prediction.length; i++) {
                    double error = prediction[i] - target[i];
                    total += error * error;
                }
                return total / prediction.length;
            }

            @Override
            public double[] gradient(double[] prediction, double[] target) {
                requireSameLength(prediction, target);
                double[] gradient = new double[prediction.length];
                for (int i = 0; i < prediction.length; i++) {
                    gradient[i] = 2 * (prediction[i] - target[i]) / prediction.length;
                }
                return gradient;
            }
        };
    }

    private static void requireSameLength(double[] prediction, double[] target) {
        if (prediction == null || target == null
                || prediction.length != target.length || prediction.length == 0) {
            throw new IllegalArgumentException("prediction and target must be parallel and non-empty");
        }
    }
}
