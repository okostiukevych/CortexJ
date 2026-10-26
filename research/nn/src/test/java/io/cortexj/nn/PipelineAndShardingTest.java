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
        // 2 stages x 3 micro-batches = 6 forward + 6 backward = 12 steps
        assertEquals(12, steps.size());
        long forwardCount = steps.stream()
                .filter(s -> s.direction() == PipelineSchedule.Step.Direction.FORWARD)
                .count();
        long backwardCount = steps.stream()
                .filter(s -> s.direction() == PipelineSchedule.Step.Direction.BACKWARD)
                .count();
        assertEquals(6L, forwardCount);
        assertEquals(6L, backwardCount);

        // Warmup runs 2 steps forward (micro-batch 0 across both stages), then steady-state interleaves 1F and 1B:
        // index 0: F(m0, s0), index 1: F(m0, s1), index 2: F(m1, s0), index 3: B(m0, s1), index 4: F(m1, s1), index 5: B(m0, s0)
        assertEquals(PipelineSchedule.Step.Direction.FORWARD, steps.get(0).direction());
        assertEquals(PipelineSchedule.Step.Direction.BACKWARD, steps.get(3).direction());
        assertEquals(0, steps.get(3).microBatch());
        assertEquals(1, steps.get(3).stage());
        assertEquals(PipelineSchedule.Step.Direction.FORWARD, steps.get(4).direction());
        assertEquals(1, steps.get(4).microBatch());
        assertEquals(PipelineSchedule.Step.Direction.BACKWARD, steps.get(5).direction());
        assertEquals(0, steps.get(5).microBatch());
        assertEquals(0, steps.get(5).stage());

        // Verify causal topological ordering for every micro-batch:
        // F(m, 0) < F(m, 1) < B(m, 1) < B(m, 0)
        for (int m = 0; m < 3; m++) {
            int f0 = indexOf(steps, m, 0, PipelineSchedule.Step.Direction.FORWARD);
            int f1 = indexOf(steps, m, 1, PipelineSchedule.Step.Direction.FORWARD);
            int b1 = indexOf(steps, m, 1, PipelineSchedule.Step.Direction.BACKWARD);
            int b0 = indexOf(steps, m, 0, PipelineSchedule.Step.Direction.BACKWARD);
            assertTrue(f0 < f1 && f1 < b1 && b1 < b0,
                    "invalid causal order for micro-batch " + m);
        }
    }

    private static int indexOf(List<PipelineSchedule.Step> steps, int microBatch, int stage,
                               PipelineSchedule.Step.Direction direction) {
        for (int i = 0; i < steps.size(); i++) {
            PipelineSchedule.Step step = steps.get(i);
            if (step.microBatch() == microBatch && step.stage() == stage && step.direction() == direction) {
                return i;
            }
        }
        return -1;
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
