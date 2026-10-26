package io.cortexj.nn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Phase C pipeline parallel (ADR-027): stage assignment over Module
 * submodules with a micro-batch 1F1B schedule. The schedule order is
 * exact (tested); cross-device transport is the caller's wiring —
 * this scheduler computes WHAT runs WHERE and WHEN.
 */
public final class PipelineSchedule {

    /**
     * One scheduled step: stage index, micro-batch index, forward or
     * backward.
     */
    public static final class Step {

        public enum Direction {
            FORWARD,
            BACKWARD
        }

        private final int stage;
        private final int microBatch;
        private final Direction direction;

        public Step(int stage, int microBatch, Direction direction) {
            this.stage = stage;
            this.microBatch = microBatch;
            this.direction = direction;
        }

        public int stage() {
            return stage;
        }

        public int microBatch() {
            return microBatch;
        }

        public Direction direction() {
            return direction;
        }

        @Override
        public String toString() {
            return "stage " + stage + " micro " + microBatch + " " + direction;
        }
    }

    private PipelineSchedule() {
    }

    /**
     * Assigns submodule indices to stages round-robin: stage s owns
     * submodules s, s + stages, ... Returns the stage per submodule.
     */
    public static List<Integer> assignStages(int submodules, int stages) {
        if (submodules < 1 || stages < 1) {
            throw new IllegalArgumentException("submodules and stages must be positive");
        }
        if (stages > submodules) {
            throw new IllegalArgumentException("more stages than submodules");
        }
        List<Integer> assignment = new ArrayList<>(submodules);
        for (int i = 0; i < submodules; i++) {
            assignment.add(i % stages);
        }
        return Collections.unmodifiableList(assignment);
    }

    /**
     * 1F1B schedule: warmup forwards until the first micro-batch reaches
     * the final stage, then steady-state alternating one forward and one
     * backward step, then cooldown backwards for the remaining in-flight
     * micro-batches. Deterministic for the same inputs.
     */
    public static List<Step> schedule(int stages, int microBatches) {
        if (stages < 1 || microBatches < 1) {
            throw new IllegalArgumentException("stages and micro-batches must be positive");
        }
        List<Step> forwards = new ArrayList<>(stages * microBatches);
        for (int micro = 0; micro < microBatches; micro++) {
            for (int stage = 0; stage < stages; stage++) {
                forwards.add(new Step(stage, micro, Step.Direction.FORWARD));
            }
        }
        List<Step> backwards = new ArrayList<>(stages * microBatches);
        for (int micro = 0; micro < microBatches; micro++) {
            for (int stage = stages - 1; stage >= 0; stage--) {
                backwards.add(new Step(stage, micro, Step.Direction.BACKWARD));
            }
        }
        int warmup = Math.min(stages, forwards.size());
        List<Step> steps = new ArrayList<>(2 * stages * microBatches);
        for (int i = 0; i < warmup; i++) {
            steps.add(forwards.get(i));
        }
        for (int i = warmup; i < forwards.size(); i++) {
            steps.add(forwards.get(i));
            steps.add(backwards.get(i - warmup));
        }
        for (int i = forwards.size() - warmup; i < backwards.size(); i++) {
            steps.add(backwards.get(i));
        }
        return Collections.unmodifiableList(steps);
    }
}
