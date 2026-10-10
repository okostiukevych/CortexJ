package io.cortexj.nn;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase C pipeline + sharded optimizer (ADR-027): schedule order,
 * stage assignment, moment parity and gather.
 */
class PipelineAndShardingTest {

    @Test
    void scheduleIsOneForwardOneBackward() {
        List<PipelineSchedule.Step> steps = PipelineSchedule.schedule(2, 3);
        // 2 stages x 3 micro-batches forward, then the same backward
        assertEquals(12, steps.size());
        for (int i = 0; i < 6; i++) {
            assertEquals(PipelineSchedule.Step.Direction.FORWARD, steps.get(i).direction());
        }
        for (int i = 6; i < 12; i++) {
            assertEquals(PipelineSchedule.Step.Direction.BACKWARD, steps.get(i).direction());
        }
        // forwards run micro-batch by micro-batch, all stages in order
        assertEquals(0, steps.get(0).stage());
        assertEquals(0, steps.get(0).microBatch());
        assertEquals(1, steps.get(1).stage());
        assertEquals(0, steps.get(1).microBatch());
        assertEquals(0, steps.get(2).stage());
        assertEquals(1, steps.get(2).microBatch());
        // backwards run in reverse micro-batch order
        assertEquals(2, steps.get(6).microBatch());
        assertEquals(1, steps.get(6).stage());
        assertEquals(0, steps.get(11).microBatch());
        assertEquals(0, steps.get(11).stage());
    }

    @Test
    void stageAssignmentIsRoundRobin() {
        List<Integer> assignment = PipelineSchedule.assignStages(5, 2);
        assertEquals(java.util.Arrays.asList(0, 1, 0, 1, 0), assignment);
        assertThrows(IllegalArgumentException.class,
                () -> PipelineSchedule.assignStages(2, 3));
    }

    @Test
    void shardedMomentsMatchUnsharded() {
        ShardedMoments sharded = new ShardedMoments(4, 2, 0.9, 0.999);
        double[] gradients = {1.0, 2.0, 3.0, 4.0};
        sharded.accumulate(gradients);
        sharded.accumulate(gradients);
        double[][] gathered = sharded.gather();
        // beta1=0.9: m = 0.1*g + 0.9*(0.1*g) = 0.19*g after two steps
        assertEquals(0.19 * 1.0, gathered[0][0], 1e-9);
        assertEquals(0.19 * 4.0, gathered[0][3], 1e-9);
        // beta2=0.999: v = 0.001*g^2 + 0.999*0.001*g^2
        assertEquals(0.001999 * 1.0, gathered[1][0], 1e-9);
        assertEquals(0.001999 * 16.0, gathered[1][3], 1e-9);
        // shards split 2/2
        assertEquals(2, sharded.workerShardSize(0));
        assertEquals(2, sharded.workerShardSize(1));
    }

    @Test
    void unevenShardsGiveRemainderToLastWorker() {
        ShardedMoments sharded = new ShardedMoments(5, 2, 0.9, 0.999);
        assertEquals(2, sharded.workerShardSize(0));
        assertEquals(3, sharded.workerShardSize(1));
        assertThrows(IllegalArgumentException.class,
                () -> sharded.accumulate(new double[]{1.0}));
    }
}
