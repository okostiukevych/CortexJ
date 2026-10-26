package io.cortexj.nn;

import io.cortexj.autograd.Autograd;
import io.cortexj.autograd.Variable;
import io.cortexj.tensor.Shape;
import io.cortexj.tensor.TensorBackend;
import io.cortexj.tensor.cpu.CpuBackend;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase C tensor parallel (ADR-027): sharded forward parity against
 * the unsharded layer, for both split modes.
 */
class ShardedLinearTest {

    private static final TensorBackend BACKEND = new CpuBackend();

    @Test
    void columnSplitShardsMatchUnsharded() {
        Autograd autograd = new Autograd(BACKEND);
        ShardedLinear layer = new ShardedLinear(autograd, 2, 4, 2,
                ShardedLinear.Split.COLUMN, Initialization.constant(0.5));
        Linear reference = new Linear(autograd, 2, 4, Initialization.constant(0.5));
        Variable x = autograd.constant(new double[]{1.0, 2.0}, Shape.of(2, 1));

        Variable full = (Variable) reference.forward(x);
        Variable first = layer.shardedForward(x, 0);
        Variable second = layer.shardedForward(x, 1);
        double[] fullData = full.data().toArray();
        double[] firstData = first.data().toArray();
        double[] secondData = second.data().toArray();
        // column split: shard outputs concatenate to the full output
        assertEquals(4, fullData.length);
        assertEquals(2, firstData.length);
        assertEquals(2, secondData.length);
        assertEquals(fullData[0], firstData[0], 1e-9);
        assertEquals(fullData[1], firstData[1], 1e-9);
        assertEquals(fullData[2], secondData[0], 1e-9);
        assertEquals(fullData[3], secondData[1], 1e-9);
    }

    @Test
    void rowSplitPartialSumsMatchUnsharded() {
        Autograd autograd = new Autograd(BACKEND);
        ShardedLinear layer = new ShardedLinear(autograd, 2, 2, 2,
                ShardedLinear.Split.ROW, Initialization.constant(0.5));
        Linear reference = new Linear(autograd, 2, 2, Initialization.constant(0.5));
        Variable x = autograd.constant(new double[]{1.0, 2.0}, Shape.of(2, 1));

        Variable full = (Variable) reference.forward(x);
        Variable first = layer.shardedForward(x, 0);
        Variable second = layer.shardedForward(x, 1);
        double[] fullData = full.data().toArray();
        double[] firstData = first.data().toArray();
        double[] secondData = second.data().toArray();
        // row split: partial sums add to the full output
        assertEquals(fullData[0], firstData[0] + secondData[0], 1e-9);
        assertEquals(fullData[1], firstData[1] + secondData[1], 1e-9);
    }

    @Test
    void shardCountMustDivideEvenly() {
        Autograd autograd = new Autograd(BACKEND);
        try {
            new ShardedLinear(autograd, 2, 3, 2, ShardedLinear.Split.COLUMN,
                    Initialization.zeros());
            assertTrue(false, "uneven column split must fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("evenly"));
        }
        try {
            layerShard(new ShardedLinear(autograd, 2, 2, 2, ShardedLinear.Split.ROW,
                    Initialization.zeros()), 2);
            assertTrue(false, "out-of-range shard must fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("range"));
        }
    }

    private static Variable layerShard(ShardedLinear layer, int shard) {
        return layer.shardedForward(new Autograd(BACKEND).constant(
                new double[]{1.0, 2.0}, Shape.of(2, 1)), shard);
    }

    @Test
    void shardedBackwardPropagatesGradientsMatchingUnsharded() {
        Autograd autograd = new Autograd(BACKEND);
        ShardedLinear colLayer = new ShardedLinear(autograd, 2, 4, 2,
                ShardedLinear.Split.COLUMN, Initialization.constant(0.5));
        Linear refCol = new Linear(autograd, 2, 4, Initialization.constant(0.5));
        Variable x1 = autograd.variable(new double[]{1.0, 2.0}, Shape.of(2, 1));
        Variable x2 = autograd.variable(new double[]{1.0, 2.0}, Shape.of(2, 1));

        Variable outSharded = (Variable) colLayer.forward(x1);
        autograd.backward(autograd.sum(outSharded));
        Variable outRef = (Variable) refCol.forward(x2);
        autograd.backward(autograd.sum(outRef));

        for (int i = 0; i < 8; i++) {
            assertEquals(refCol.weight().grad()[i], colLayer.weight().grad()[i], 1e-9);
        }
        for (int i = 0; i < 4; i++) {
            assertEquals(refCol.bias().grad()[i], colLayer.bias().grad()[i], 1e-9);
        }
        assertEquals(x2.grad()[0], x1.grad()[0], 1e-9);
        assertEquals(x2.grad()[1], x1.grad()[1], 1e-9);
    }
}
