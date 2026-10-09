package io.cortexj.train;

/**
 * Learning-rate schedule over training steps.
 */
public interface LearningRateSchedule {

    String id();

    double rateAt(long step);
}
