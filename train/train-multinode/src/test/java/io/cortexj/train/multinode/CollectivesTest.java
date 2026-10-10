package io.cortexj.train.multinode;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase C collectives (ADR-027): all-gather and reduce-scatter over
 * real sockets, parity against single-node reference math.
 */
class CollectivesTest {

    @Test
    void allGatherConcatenatesInWorkerOrder() throws Exception {
        try (GradientWorker first = new GradientWorker();
             GradientWorker second = new GradientWorker()) {
            first.start();
            second.start();
            Collectives collectives = new Collectives(
                    Arrays.asList(first.endpoint(), second.endpoint()), 10_000);
            try {
                double[][] gathered = collectives.allGather(
                        Arrays.asList(new double[]{1.0, 2.0}, new double[]{3.0}));
                assertEquals(2, gathered.length);
                assertArrayEquals(new double[]{1.0, 2.0}, gathered[0], 1e-12);
                assertArrayEquals(new double[]{3.0}, gathered[1], 1e-12);
            } finally {
                collectives.close();
            }
        }
    }

    @Test
    void reduceScatterSumsThenSplits() throws Exception {
        try (GradientWorker first = new GradientWorker();
             GradientWorker second = new GradientWorker()) {
            first.start();
            second.start();
            Collectives collectives = new Collectives(
                    Arrays.asList(first.endpoint(), second.endpoint()), 10_000);
            try {
                // contributions [1,2,3,4] + [5,6,7,8] = [6,8,10,12];
                // split into [6,8] and [10,12]
                double[][] scattered = collectives.reduceScatter(
                        Arrays.asList(
                                new double[]{1.0, 2.0, 3.0, 4.0},
                                new double[]{5.0, 6.0, 7.0, 8.0}));
                assertEquals(2, scattered.length);
                assertArrayEquals(new double[]{6.0, 8.0}, scattered[0], 1e-12);
                assertArrayEquals(new double[]{10.0, 12.0}, scattered[1], 1e-12);
            } finally {
                collectives.close();
            }
        }
    }

    @Test
    void deadWorkerFailsTypedWithIdentity() {
        Collectives collectives = new Collectives(
                Arrays.asList("tcp://127.0.0.1:1"), 200);
        try {
            java.io.IOException failure = assertThrows(java.io.IOException.class,
                    () -> collectives.allGather(
                            Arrays.asList(new double[]{1.0})));
            assertTrue(failure.getMessage().contains("tcp://127.0.0.1:1"),
                    failure.getMessage());
        } finally {
            collectives.close();
        }
    }
}
