package io.cortexj.train.djl;

import ai.djl.engine.Engine;
import io.cortexj.error.CortexJException;
import io.cortexj.train.TrainingBackend;
import io.cortexj.train.TrainingRequest;
import io.cortexj.train.TrainingResult;

import java.util.ArrayList;
import java.util.List;

/**
 * TrainingBackend wrapping DJL training capability (ADR-021). DJL
 * training requires a training engine (PyTorch) that is installed
 * separately; when it is missing, the backend reports honest
 * {@code unavailable} with remediation. LLM LoRA support is
 * engine-dependent, so a present engine reports {@code partial}.
 */
public class DjlTrainingBackend implements TrainingBackend {

    public static final String BACKEND_ID = "djl";

    @Override
    public String id() {
        return BACKEND_ID;
    }

    @Override
    public Support evaluate(TrainingRequest request) {
        java.util.Set<String> engines;
        try {
            engines = new java.util.LinkedHashSet<String>(Engine.getAllEngines());
        } catch (Throwable discoveryFailure) {
            return new Support(Level.UNSUPPORTED,
                    "DJL engine discovery failed on this machine: " + discoveryFailure,
                    0);
        }
        if (engines.isEmpty()) {
            return new Support(Level.UNSUPPORTED,
                    "no DJL training engine is installed on the classpath",
                    0);
        }
        boolean trainingCapable = false;
        for (String engine : engines) {
            if ("PyTorch".equalsIgnoreCase(engine) || "MXNet".equalsIgnoreCase(engine)
                    || "TensorFlow".equalsIgnoreCase(engine)) {
                trainingCapable = true;
                break;
            }
        }
        if (!trainingCapable) {
            return new Support(Level.UNSUPPORTED,
                    "DJL engines present (" + engines + ") do not support training; "
                            + "add the PyTorch engine",
                    0);
        }
        if (request != null && request.strategy() == io.cortexj.train.TrainingStrategy.FULL) {
            return new Support(Level.PARTIAL,
                    "DJL training covers classic models; full LLM pretraining is not turnkey",
                    0);
        }
        // LoRA/QLoRA through DJL: engine present, PEFT support engine-dependent
        return new Support(Level.PARTIAL,
                "DJL training engines present (" + engines + "); LLM PEFT support is "
                        + "engine-dependent and validated per model",
                estimateMemory(request));
    }

    @Override
    public TrainingResult train(TrainingRequest request, Support support) {
        // reached only with FULL support, which DJL never reports in 0.x;
        // a caller forcing this path gets a typed error instead of a guess
        CortexJException failure = new CortexJException(
                "CORTEXJ-TRAIN-0001",
                "DJL training backend does not fully support this run in 0.x",
                "Use a remote trainer provider or wait for the DJL LoRA path.");
        failure.detail("backend", id());
        failure.detail("support", support == null ? "null" : support.level().name());
        throw failure;
    }

    private long estimateMemory(TrainingRequest request) {
        // rough floor: model parameters + optimizer state is backend-owned;
        // the honest 0.x estimate is unknown until the engine reports
        return 0;
    }

    static List<String> remediation() {
        List<String> steps = new ArrayList<String>();
        steps.add("Add ai.djl.pytorch:pytorch-engine and pytorch-model-zoo to the assembly");
        steps.add("Or use a remote trainer provider");
        return steps;
    }
}
