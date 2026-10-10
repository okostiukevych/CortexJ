package io.cortexj.train;

import io.cortexj.error.CortexJException;
import io.cortexj.meta.ModelIdentity;

import java.util.ArrayList;
import java.util.List;

/**
 * High-level training entry point: validate compatibility and memory
 * BEFORE any expensive work (fail-fast, same policy as runtime
 * planning), then start the job on a backend chosen through the SPI.
 */
public final class TrainingJobs {

    private final List<TrainingBackend> backends;

    public TrainingJobs(List<TrainingBackend> backends) {
        if (backends == null) {
            throw new IllegalArgumentException("backends must not be null");
        }
        this.backends = new ArrayList<TrainingBackend>(backends);
    }

    /**
     * Validates the request against every registered backend and returns
     * the compatibility report: which backends support this run and why
     * the others decline. Available without starting anything.
     */
    public List<TrainingBackend.Support> evaluate(TrainingRequest request) {
        List<String> intentProblems = TrainingRequest.validateIntent(request);
        if (!intentProblems.isEmpty()) {
            throw new CortexJException(io.cortexj.ErrorCode.PLAN_USER_REQUIREMENT_IMPOSSIBLE,
                    "training request is invalid: " + intentProblems);
        }
        List<TrainingBackend.Support> report = new ArrayList<TrainingBackend.Support>();
        for (TrainingBackend backend : backends) {
            report.add(backend.evaluate(request));
        }
        return report;
    }

    /**
     * Runs the training job: picks the best supporting backend (first
     * FULL support wins; explicit backends stay selectable through
     * {@code requireBackend}), validates before allocating, executes.
     */
    public TrainingResult start(TrainingRequest request) {
        return start(request, null);
    }

    /**
     * Runs a composed plan through the same selection (the
     * high-level example shape, minus the Runtime facade the
     * dependency law forbids in the stable api).
     */
    public TrainingResult start(TrainingPlan plan) {
        if (plan == null || plan.request() == null) {
            throw new CortexJException(io.cortexj.ErrorCode.PLAN_USER_REQUIREMENT_IMPOSSIBLE,
                    "training plan is invalid: a request is required");
        }
        return start(plan.request());
    }

    /**
     * Runs the training job with a metrics listener: every backend
     * metric is forwarded as it arrives (the Experiments bridge
     * consumes this listener).
     */
    public TrainingResult start(TrainingRequest request, TrainingListener listener) {
        List<String> intentProblems = TrainingRequest.validateIntent(request);
        if (!intentProblems.isEmpty()) {
            throw new CortexJException(io.cortexj.ErrorCode.PLAN_USER_REQUIREMENT_IMPOSSIBLE,
                    "training request is invalid: " + intentProblems);
        }
        String required = request.parameters().get("requireBackend");
        TrainingBackend chosen = null;
        TrainingBackend.Support chosenSupport = null;
        List<String> declines = new ArrayList<String>();
        for (TrainingBackend backend : backends) {
            TrainingBackend.Support support = backend.evaluate(request);
            if (required != null && !required.equals(backend.id())) {
                continue;
            }
            if (support.level() == TrainingBackend.Level.FULL) {
                chosen = backend;
                chosenSupport = support;
                break;
            }
            declines.add(backend.id() + ": " + support.reason());
        }
        if (chosen == null) {
            CortexJException failure = new CortexJException(
                    io.cortexj.ErrorCode.PLAN_NO_CANDIDATE,
                    "no training backend supports this run",
                    "Add a training backend module or relax the request.");
            failure.detail("declines", declines.toString());
            if (required != null) {
                failure.detail("required", required);
            }
            throw failure;
        }
        TrainingResult result = chosen.train(request, chosenSupport);
        if (listener != null) {
            for (java.util.Map.Entry<String, Double> metric : result.metrics().entrySet()) {
                listener.onMetric(result.jobId(), metric.getKey(), metric.getValue());
            }
            listener.onComplete(result);
        }
        return result;
    }

    /**
     * Async job lifecycle: runs {@link #start(TrainingRequest,
     * TrainingListener)} on the given executor. Cancellation interrupts
     * the worker thread (best-effort: backends check interruption
     * between epochs, never mid-kernel).
     */
    public java.util.concurrent.CompletionStage<TrainingResult> startAsync(
            TrainingRequest request, java.util.concurrent.Executor executor) {
        return startAsync(request, null, executor);
    }

    public java.util.concurrent.CompletionStage<TrainingResult> startAsync(
            TrainingRequest request, TrainingListener listener,
            java.util.concurrent.Executor executor) {
        if (executor == null) {
            throw new IllegalArgumentException("executor must not be null");
        }
        return java.util.concurrent.CompletableFuture.supplyAsync(
                () -> start(request, listener), executor);
    }

    /**
     * Builds the model identity for a training run: the model id plus the
     * resolved revision carried by the request (null while unresolved).
     */
    public static ModelIdentity identityFor(TrainingRequest request, String revision) {
        return ModelIdentity.of(request.model(), revision);
    }
}
