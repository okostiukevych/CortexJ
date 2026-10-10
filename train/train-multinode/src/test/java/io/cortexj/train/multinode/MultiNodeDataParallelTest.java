package io.cortexj.train.multinode;

import io.cortexj.train.distributed.DataParallel;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase B parity (ADR-027): multi-node average over real sockets
 * equals the single-worker full batch; a dead worker fails typed
 * with its identity after the reconnect retry.
 */
class MultiNodeDataParallelTest {

    @Test
    void multiNodeAverageEqualsSingleWorkerBatch() throws Exception {
        double[] weights = {0.5, -1.0};
        double[] batch = {1.0, 2.0, -0.5, 3.0};
        double[][] shards = {{1.0, 2.0}, {-0.5, 3.0}};
        try (GradientWorker first = new GradientWorker();
             GradientWorker second = new GradientWorker()) {
            first.start();
            second.start();
            MultiNodeDataParallel driver = new MultiNodeDataParallel(
                    Arrays.asList(first.endpoint(), second.endpoint()), 10_000);
            double[] averaged = driver.gradients(weights, shards);
            double[] fullBatch = new DataParallel().gradients(weights, batch, 1);
            for (int i = 0; i < fullBatch.length; i++) {
                assertEquals(fullBatch[i], averaged[i], 1e-9,
                        "multi-node average must equal the full batch at " + i);
            }
        }
    }

    @Test
    void deadWorkerFailsTypedWithIdentity() {
        MultiNodeDataParallel driver = new MultiNodeDataParallel(
                Arrays.asList("tcp://127.0.0.1:1"), 200);
        java.io.IOException failure = assertThrows(java.io.IOException.class,
                () -> driver.gradients(new double[]{0.5}, new double[][]{{1.0}}));
        assertTrue(failure.getMessage().contains("tcp://127.0.0.1:1"),
                failure.getMessage());
    }
}
