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
        return chosen.train(request, chosenSupport);
    }

    /**
     * Builds the model identity for a training run: the model id plus the
     * resolved revision carried by the request (null while unresolved).
     */
    public static ModelIdentity identityFor(TrainingRequest request, String revision) {
        return ModelIdentity.of(request.model(), revision);
    }
}
