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
     * 1F1B schedule: all forwards in stage/micro-batch order, then all
     * backwards in reverse micro-batch order per stage. Deterministic
     * for the same inputs.
     */
    public static List<Step> schedule(int stages, int microBatches) {
        if (stages < 1 || microBatches < 1) {
            throw new IllegalArgumentException("stages and micro-batches must be positive");
        }
        List<Step> steps = new ArrayList<>();
        for (int micro = 0; micro < microBatches; micro++) {
            for (int stage = 0; stage < stages; stage++) {
                steps.add(new Step(stage, micro, Step.Direction.FORWARD));
            }
        }
        for (int micro = microBatches - 1; micro >= 0; micro--) {
            for (int stage = stages - 1; stage >= 0; stage--) {
                steps.add(new Step(stage, micro, Step.Direction.BACKWARD));
            }
        }
        return Collections.unmodifiableList(steps);
    }
}
