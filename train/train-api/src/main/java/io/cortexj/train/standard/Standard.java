package io.cortexj.train.standard;

import io.cortexj.train.LearningRateSchedule;
import io.cortexj.train.Optimizer;

/**
 * Standard policy implementations: SGD with optional momentum,
 * constant and warmup-decay schedules, step-based checkpointing and
 * early stopping. Pure math, unit-tested.
 */
public final class Standard {

    private Standard() {
    }

    public static Optimizer sgd(double learningRate) {
        return sgd(learningRate, 0.0);
    }

    public static Optimizer sgd(double learningRate, double momentum) {
        if (learningRate <= 0 || learningRate > 1) {
            throw new IllegalArgumentException("learning rate must sit in (0, 1]");
        }
        if (momentum < 0 || momentum >= 1) {
            throw new IllegalArgumentException("momentum must sit in [0, 1)");
        }
        return new Optimizer() {
            private double[] velocity;

            @Override
            public String id() {
                return momentum > 0 ? "sgd-momentum" : "sgd";
            }

            @Override
            public double[] step(double[] parameters, double[] gradients) {
                if (gradients.length != parameters.length) {
                    throw new IllegalArgumentException("gradient shape mismatch");
                }
                if (velocity == null) {
                    velocity = new double[parameters.length];
                }
                double[] updated = new double[parameters.length];
                for (int i = 0; i < parameters.length; i++) {
                    velocity[i] = momentum * velocity[i] - learningRate * gradients[i];
                    updated[i] = parameters[i] + velocity[i];
                }
                return updated;
            }
        };
    }

    public static LearningRateSchedule constant(double rate) {
        if (rate <= 0) {
            throw new IllegalArgumentException("rate must be positive");
        }
        return new LearningRateSchedule() {
            @Override
            public String id() {
                return "constant";
            }

            @Override
            public double rateAt(long step) {
                return rate;
            }
        };
    }

    /**
     * Linear warmup to the peak rate over {@code warmupSteps}, then
     * linear decay to zero over the remaining steps.
     */
    public static LearningRateSchedule warmupDecay(long warmupSteps, long totalSteps, double peakRate) {
        if (warmupSteps < 0 || totalSteps < 1 || warmupSteps > totalSteps) {
            throw new IllegalArgumentException("schedule bounds are invalid");
        }
        if (peakRate <= 0) {
            throw new IllegalArgumentException("peak rate must be positive");
        }
        return new LearningRateSchedule() {
            @Override
            public String id() {
                return "warmup-decay";
            }

            @Override
            public double rateAt(long step) {
                if (step < 0) {
                    return 0;
                }
                if (step < warmupSteps) {
                    return peakRate * (step + 1) / warmupSteps;
                }
                long remaining = totalSteps - step;
                long decayWindow = totalSteps - warmupSteps;
                if (decayWindow <= 0 || remaining <= 0) {
                    return 0;
                }
                return peakRate * remaining / decayWindow;
            }
        };
    }

    public static io.cortexj.train.CheckpointPolicy checkpointEvery(long every, int keep) {
        if (every < 1) {
            throw new IllegalArgumentException("checkpoint interval must be at least 1");
        }
        return new io.cortexj.train.CheckpointPolicy() {
            @Override
            public String id() {
                return "every-" + every;
            }

            @Override
            public boolean checkpointAt(long step, long totalSteps) {
                return step % every == 0 || step == totalSteps;
            }

            @Override
            public int keep() {
                return keep;
            }
        };
    }

    public static io.cortexj.train.EvaluationPolicy earlyStopping(long evaluateEvery,
                                                                  long maxStepsWithoutImprovement) {
        return new io.cortexj.train.EvaluationPolicy() {
            @Override
            public String id() {
                return "early-stopping";
            }

            @Override
            public boolean evaluateAt(long step, long totalSteps) {
                return step % evaluateEvery == 0 || step == totalSteps;
            }

            @Override
            public boolean shouldStop(double[] recentLosses, long limit) {
                if (recentLosses.length < limit + 1) {
                    return false;
                }
                double best = Double.POSITIVE_INFINITY;
                for (int i = 0; i < recentLosses.length - (int) limit; i++) {
                    best = Math.min(best, recentLosses[i]);
                }
                for (int i = recentLosses.length - (int) limit; i < recentLosses.length; i++) {
                    if (recentLosses[i] < best) {
                        return false;
                    }
                }
                return true;
            }
        };
    }

    public static io.cortexj.train.MixedPrecisionPolicy mixedPrecision(
            io.cortexj.train.MixedPrecisionPolicy.Precision requested, boolean fallbackAllowed) {
        return new io.cortexj.train.MixedPrecisionPolicy() {
            @Override
            public String id() {
                return "mixed-" + requested.name().toLowerCase();
            }

            @Override
            public io.cortexj.train.MixedPrecisionPolicy.Precision requested() {
                return requested;
            }

            @Override
            public boolean fallbackAllowed() {
                return fallbackAllowed;
            }
        };
    }
}
