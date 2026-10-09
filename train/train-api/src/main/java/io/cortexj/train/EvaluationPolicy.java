package io.cortexj.train;

/**
 * Evaluation policy: when a run evaluates and when it stops early.
 */
public interface EvaluationPolicy {

    String id();

    /**
     * True when an evaluation must run at the given step.
     */
    boolean evaluateAt(long step, long totalSteps);

    /**
     * Early stopping: true when the run should stop given the recent
     * evaluation losses (caller-defined window).
     */
    boolean shouldStop(double[] recentLosses, long maxStepsWithoutImprovement);
}
