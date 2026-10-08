package io.cortexj.train.peft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * LoRA hyperparameters: rank, alpha, dropout and the target module
 * names. Immutable; validated at construction.
 */
public final class LoRAConfig {

    private final int rank;
    private final int alpha;
    private final double dropout;
    private final List<String> targetModules;

    public LoRAConfig(int rank, int alpha, double dropout, List<String> targetModules) {
        if (rank < 1) {
            throw new IllegalArgumentException("rank must be at least 1");
        }
        if (alpha < 1) {
            throw new IllegalArgumentException("alpha must be at least 1");
        }
        if (dropout < 0 || dropout >= 1) {
            throw new IllegalArgumentException("dropout must sit in [0, 1)");
        }
        if (targetModules == null || targetModules.isEmpty()) {
            throw new IllegalArgumentException("at least one target module is required");
        }
        this.rank = rank;
        this.alpha = alpha;
        this.dropout = dropout;
        this.targetModules = Collections.unmodifiableList(
                new ArrayList<String>(targetModules));
    }

    public int rank() {
        return rank;
    }

    public int alpha() {
        return alpha;
    }

    public double dropout() {
        return dropout;
    }

    public List<String> targetModules() {
        return targetModules;
    }

    public double scaling() {
        return (double) alpha / rank;
    }
}
