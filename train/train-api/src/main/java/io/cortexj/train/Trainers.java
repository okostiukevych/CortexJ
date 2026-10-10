package io.cortexj.train;

import io.cortexj.train.standard.CrossEntropy;
import io.cortexj.train.standard.Standard;

/**
 * High-level training entry point (the train-track facade): builds a
 * plan from intent plus explicit policies. The stable Runtime facade
 * never references this (dependency law); train users start here.
 */
public final class Trainers {

    private Trainers() {
    }

    public static Builder trainer() {
        return new Builder();
    }

    public static final class Builder {

        private String model;
        private String dataset;
        private TrainingStrategy strategy = TrainingStrategy.FULL;
        private int epochs = 1;
        private Optimizer optimizer;
        private LossFunction loss;
        private LearningRateSchedule schedule;
        private CheckpointPolicy checkpoints;
        private EvaluationPolicy evaluation;
        private MixedPrecisionPolicy mixedPrecision;
        private Device device = Device.AUTO;

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder dataset(String dataset) {
            this.dataset = dataset;
            return this;
        }

        public Builder strategy(TrainingStrategy strategy) {
            if (strategy != null) {
                this.strategy = strategy;
            }
            return this;
        }

        public Builder epochs(int epochs) {
            this.epochs = epochs;
            return this;
        }

        public Builder optimizer(Optimizer optimizer) {
            this.optimizer = optimizer;
            return this;
        }

        public Builder loss(LossFunction loss) {
            this.loss = loss;
            return this;
        }

        public Builder schedule(LearningRateSchedule schedule) {
            this.schedule = schedule;
            return this;
        }

        public Builder checkpoints(CheckpointPolicy checkpoints) {
            this.checkpoints = checkpoints;
            return this;
        }

        public Builder evaluation(EvaluationPolicy evaluation) {
            this.evaluation = evaluation;
            return this;
        }

        public Builder mixedPrecision(MixedPrecisionPolicy mixedPrecision) {
            this.mixedPrecision = mixedPrecision;
            return this;
        }

        public Builder device(Device device) {
            if (device != null) {
                this.device = device;
            }
            return this;
        }

        public TrainingPlan build() {
            TrainingRequest.Builder request = TrainingRequest.builder()
                    .epochs(epochs);
            if (model != null) {
                request.model(model);
            }
            if (dataset != null) {
                request.dataset(dataset);
            }
            request.strategy(strategy);
            return new TrainingPlan(
                    request.build(),
                    optimizer == null ? Standard.sgd(1e-4) : optimizer,
                    loss == null ? new CrossEntropy() : loss,
                    schedule, checkpoints, evaluation, mixedPrecision, device);
        }
    }
}
