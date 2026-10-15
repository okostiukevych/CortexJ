package io.cortexj.train;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * LoRA hyperparameters on the training request (rank, alpha, target
 * module names). The artifact-side LoRAConfig lives in train-peft;
 * the backend maps these settings onto it at export time.
 */
public final class LoraSettings {

    private final int rank;
    private final int alpha;
    private final List<String> targetModules;

    private LoraSettings(int rank, int alpha, List<String> targetModules) {
        if (rank < 1) {
            throw new IllegalArgumentException("rank must be at least 1");
        }
        if (alpha < 1) {
            throw new IllegalArgumentException("alpha must be at least 1");
        }
        if (targetModules == null || targetModules.isEmpty()) {
            throw new IllegalArgumentException("at least one target module is required");
        }
        this.rank = rank;
        this.alpha = alpha;
        this.targetModules = Collections.unmodifiableList(new ArrayList<String>(targetModules));
    }

    public static LoraSettings of(int rank, int alpha, List<String> targetModules) {
        return new LoraSettings(rank, alpha, targetModules);
    }

    public int rank() {
        return rank;
    }

    public int alpha() {
        return alpha;
    }

    public List<String> targetModules() {
        return targetModules;
    }

    public float scaling() {
        return (float) ((double) alpha / rank);
    }
}
