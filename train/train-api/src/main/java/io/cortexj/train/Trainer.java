package io.cortexj.train;

/**
 * The trainer facade: builds a plan and fits a dataset through the
 * selected backend. Synchronous and cancellable.
 */
public interface Trainer {

    TrainingPlan plan();

    TrainingResult fit(TrainingPlan plan);
}
