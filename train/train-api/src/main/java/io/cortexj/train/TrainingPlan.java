package io.cortexj.train;

/**
 * The training plan: request plus the selected policies. Immutable;
 * the planner-free composition the user controls (progressive
 * disclosure: defaults exist, every piece is overridable).
 */
public final class TrainingPlan {

    private final TrainingRequest request;
    private final Optimizer optimizer;
    private final LossFunction loss;
    private final LearningRateSchedule schedule;
    private final CheckpointPolicy checkpoints;
    private final EvaluationPolicy evaluation;
    private final MixedPrecisionPolicy mixedPrecision;
    private final Device device;

    public TrainingPlan(TrainingRequest request, Optimizer optimizer, LossFunction loss,
                        LearningRateSchedule schedule, CheckpointPolicy checkpoints,
                        EvaluationPolicy evaluation, MixedPrecisionPolicy mixedPrecision,
                        Device device) {
        if (request == null || optimizer == null || loss == null) {
            throw new IllegalArgumentException("request, optimizer and loss are required");
        }
        this.request = request;
        this.optimizer = optimizer;
        this.loss = loss;
        this.schedule = schedule;
        this.checkpoints = checkpoints;
        this.evaluation = evaluation;
        this.mixedPrecision = mixedPrecision;
        this.device = device == null ? Device.AUTO : device;
    }

    public TrainingRequest request() {
        return request;
    }

    public Optimizer optimizer() {
        return optimizer;
    }

    public LossFunction loss() {
        return loss;
    }

    public LearningRateSchedule schedule() {
        return schedule;
    }

    public CheckpointPolicy checkpoints() {
        return checkpoints;
    }

    public EvaluationPolicy evaluation() {
        return evaluation;
    }

    public MixedPrecisionPolicy mixedPrecision() {
        return mixedPrecision;
    }

    public Device device() {
        return device;
    }
}
