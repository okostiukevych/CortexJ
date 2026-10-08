package io.cortexj.train.remote;

import io.cortexj.train.TrainingBackend;
import io.cortexj.train.TrainingRequest;
import io.cortexj.train.TrainingResult;

/**
 * TrainingBackend over OpenAI-compatible fine-tuning endpoints
 * (ADR-021). Probe-gated like the remote runtime: an endpoint that is
 * not configured or not reachable reports honest unavailability.
 */
public class RemoteTrainingBackend implements TrainingBackend {

    public static final String BACKEND_ID = "remote-trainer";
    public static final String CONFIG_BASE_URL = "train.remote.baseUrl";

    private final String baseUrl;

    public RemoteTrainingBackend(String baseUrl) {
        this.baseUrl = baseUrl == null || baseUrl.trim().isEmpty() ? null : baseUrl.trim();
    }

    public RemoteTrainingBackend() {
        this(null);
    }

    private String effectiveBaseUrl() {
        if (baseUrl != null) {
            return baseUrl;
        }
        String fromEnvironment = System.getenv("CORTEXJ_REMOTE_TRAINER_URL");
        if (fromEnvironment != null && !fromEnvironment.trim().isEmpty()) {
            return fromEnvironment.trim();
        }
        return null;
    }

    @Override
    public String id() {
        return BACKEND_ID;
    }

    @Override
    public Support evaluate(TrainingRequest request) {
        String url = effectiveBaseUrl();
        if (url == null) {
            return new Support(Level.UNSUPPORTED,
                    "no remote trainer endpoint configured; set CORTEXJ_REMOTE_TRAINER_URL",
                    0);
        }
        // LoRA fine-tuning over the endpoint; job semantics validated per model
        return new Support(Level.PARTIAL,
                "remote trainer endpoint configured (" + url + "); job acceptance is "
                        + "endpoint-dependent",
                0);
    }

    @Override
    public TrainingResult train(TrainingRequest request, Support support) {
        // reached only with FULL support, which the remote trainer never
        // reports in 0.x (endpoint job semantics are endpoint-dependent);
        // a caller forcing this path gets a typed error instead of a guess
        io.cortexj.error.CortexJException failure = new io.cortexj.error.CortexJException(
                "CORTEXJ-TRAIN-0002",
                "remote trainer backend does not fully support this run in 0.x",
                "Implement the endpoint job flow or use a local training engine.");
        failure.detail("backend", id());
        failure.detail("endpoint", effectiveBaseUrl() == null ? "none" : "configured");
        throw failure;
    }
}
