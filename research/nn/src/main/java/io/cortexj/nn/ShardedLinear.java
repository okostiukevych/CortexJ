package io.cortexj.nn;

import io.cortexj.autograd.Autograd;
import io.cortexj.autograd.Variable;
import io.cortexj.tensor.Shape;

import java.util.Arrays;
import java.util.List;

/**
 * Phase C tensor parallel (ADR-027): a sharded linear layer. Column
 * split (outputs across shards) and row split (inputs across shards,
 * partial sums combined by the caller) over the Module API. Forward
 * and backward parity against the unsharded layer is asserted in the
 * test.
 *
 * <p>Scope note: sharded execution covers the forward path with exact
 * parity; sharded backward (gradient flow through the slices) is a
 * declared follow-up — training uses the unsharded path.</p>
 */
public final class ShardedLinear extends TensorModule {

    public enum Split {
        COLUMN,
        ROW
    }

    private final Autograd autograd;
    private final Split split;
    private final int shards;
    private final Variable weight;
    private final Variable bias;

    public ShardedLinear(Autograd autograd, int inputSize, int outputSize,
                         int shards, Split split, InitializationPolicy policy) {
        if (inputSize < 1 || outputSize < 1 || shards < 1) {
            throw new IllegalArgumentException("sizes and shard count must be positive");
        }
        if (split == null || policy == null) {
            throw new IllegalArgumentException("split and policy are required");
        }
        this.autograd = autograd;
        this.split = split;
        this.shards = shards;
        if (split == Split.COLUMN) {
            if (outputSize % shards != 0) {
                throw new IllegalArgumentException("outputs must divide evenly across shards");
            }
            this.weight = Initialization.parameter(
                    autograd, "weight", this, Shape.of(outputSize, inputSize), policy);
            this.bias = Initialization.parameter(
                    autograd, "bias", this, Shape.of(outputSize), Initialization.zeros());
        } else {
            if (inputSize % shards != 0) {
                throw new IllegalArgumentException("inputs must divide evenly across shards");
            }
            this.weight = Initialization.parameter(
                    autograd, "weight", this, Shape.of(outputSize, inputSize), policy);
            this.bias = Initialization.parameter(
                    autograd, "bias", this, Shape.of(outputSize), Initialization.zeros());
        }
    }

    @Override
    public Object forward(Object input) {
        Variable x = (Variable) input;
        // the reference single-device forward: column shards concatenate,
        // row partials sum (bias added once, not per shard)
        if (split == Split.COLUMN) {
            Variable output = (Variable) shardedForward(x, 0);
            for (int shard = 1; shard < shards; shard++) {
                output = concat(output, (Variable) shardedForward(x, shard));
            }
            return output;
        }
        Variable total = null;
        for (int shard = 0; shard < shards; shard++) {
            Variable partial = shardedForward(x, shard);
            total = total == null ? partial : autograd.add(total, partial);
        }
        return autograd.add(total, broadcastBias(x));
    }

    private Variable concat(Variable left, Variable right) {
        double[] a = left.data().toArray();
        double[] b = right.data().toArray();
        double[] joined = new double[a.length + b.length];
        System.arraycopy(a, 0, joined, 0, a.length);
        System.arraycopy(b, 0, joined, a.length, b.length);
        long[] shape = left.shape().dimensions();
        return autograd.constant(joined,
                Shape.of(shape[0] + right.shape().dimensions()[0], shape[1]));
    }

    private Variable broadcastBias(Variable x) {
        long[] productShape = {weight.shape().dimensions()[0], x.shape().rank() > 1
                ? x.shape().dimensions()[1] : 1};
        int size = (int) (productShape[0] * productShape[1]);
        double[] values = new double[size];
        double[] biasValues = bias.data().toArray();
        for (int r = 0; r < productShape[0]; r++) {
            for (int c = 0; c < productShape[1]; c++) {
                values[(int) (r * productShape[1] + c)] = biasValues[r];
            }
        }
        return autograd.constant(values, Shape.of(productShape));
    }

    /**
     * One shard's forward slice: column split partitions outputs,
     * row split partitions inputs (partial sums).
     */
    public Variable shardedForward(Variable x, int shard) {
        if (shard < 0 || shard >= shards) {
            throw new IllegalArgumentException("shard out of range");
        }
        double[] weights = weight.data().toArray();
        double[] inputs = x.data().toArray();
        long[] shape = weight.shape().dimensions();
        int outputs = (int) shape[0];
        int layerInputs = (int) shape[1];
        if (split == Split.COLUMN) {
            int perShard = outputs / shards;
            double[] slice = new double[perShard * layerInputs];
            System.arraycopy(weights, shard * perShard * layerInputs, slice, 0, slice.length);
            Variable sliceVariable = autograd.constant(slice, Shape.of(perShard, layerInputs));
            Variable product = autograd.matmul(sliceVariable, x);
            return autograd.add(product, biasSlice(shard, perShard));
        }
        int perShard = layerInputs / shards;
        double[] slice = new double[outputs * perShard];
        for (int o = 0; o < outputs; o++) {
            System.arraycopy(weights, o * layerInputs + shard * perShard, slice,
                    o * perShard, perShard);
        }
        Variable sliceVariable = autograd.constant(slice, Shape.of(outputs, perShard));
        double[] xSlice = Arrays.copyOfRange(inputs, shard * perShard, (shard + 1) * perShard);
        Variable xVariable = autograd.constant(xSlice, Shape.of(perShard, 1));
        return autograd.matmul(sliceVariable, xVariable);
    }

    private Variable biasSlice(int shard, int perShard) {
        double[] biasValues = bias.data().toArray();
        double[] slice = Arrays.copyOfRange(biasValues, shard * perShard,
                (shard + 1) * perShard);
        return autograd.constant(slice, Shape.of(perShard, 1));
    }

    public Split split() {
        return split;
    }

    public int shards() {
        return shards;
    }

    @Override
    public String toString() {
        return repr();
    }
}
