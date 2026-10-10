package io.cortexj.train.experiments;

import io.cortexj.experiments.Experiment;
import io.cortexj.train.TrainingListener;
import io.cortexj.train.TrainingResult;

/**
 * Streams training metrics into an open experiment: every backend
 * metric becomes an experiment metric; completion is a no-op (the
 * caller closes the experiment through try-with-resources).
 */
public final class ExperimentTrainingListener implements TrainingListener {

    private final Experiment experiment;

    public ExperimentTrainingListener(Experiment experiment) {
        if (experiment == null) {
            throw new IllegalArgumentException("experiment must not be null");
        }
        this.experiment = experiment;
    }

    @Override
    public void onMetric(String jobId, String name, double value) {
        experiment.metric(name, Double.toString(value));
    }

    @Override
    public void onComplete(TrainingResult result) {
        experiment.metric("artifact", result.artifactPath());
    }
}
