package io.cortexj.train;

/**
 * Metrics listener: backends forward training metrics as they arrive.
 * The Experiments bridge consumes this listener; nothing is logged by
 * default.
 */
public interface TrainingListener {

    void onMetric(String jobId, String name, double value);

    default void onComplete(TrainingResult result) {
    }
}
