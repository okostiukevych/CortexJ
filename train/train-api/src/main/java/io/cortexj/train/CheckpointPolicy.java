package io.cortexj.train;

/**
 * Checkpoint policy: when a run saves a checkpoint and how many are
 * kept. Pure contract; engines decide the storage format.
 */
public interface CheckpointPolicy {

    String id();

    /**
     * True when a checkpoint must be saved at the given step of the
     * given total.
     */
    boolean checkpointAt(long step, long totalSteps);

    /**
     * How many checkpoints are kept (0 = keep all).
     */
    int keep();
}
