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
    private final Variable[] weightShards;

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
        this.weightShards = new Variable[shards];
        if (split == Split.COLUMN) {
            if (outputSize % shards != 0) {
                throw new IllegalArgumentException("outputs must divide evenly across shards");
            }
            this.weight = Initialization.parameter(
                    autograd, "weight", this, Shape.of(outputSize, inputSize), policy);
            this.bias = Initialization.parameter(
                    autograd, "bias", this, Shape.of(outputSize), Initialization.zeros());
            int perShard = outputSize / shards;
            double[] weights = this.weight.data().toArray();
            for (int s = 0; s < shards; s++) {
                double[] slice = new double[perShard * inputSize];
                System.arraycopy(weights, s * perShard * inputSize, slice, 0, slice.length);
                this.weightShards[s] = autograd.variable(slice, Shape.of(perShard, inputSize));
            }
        } else {
            if (inputSize % shards != 0) {
                throw new IllegalArgumentException("inputs must divide evenly across shards");
            }
            this.weight = Initialization.parameter(
                    autograd, "weight", this, Shape.of(outputSize, inputSize), policy);
            this.bias = Initialization.parameter(
                    autograd, "bias", this, Shape.of(outputSize), Initialization.zeros());
            int perShard = inputSize / shards;
            double[] weights = this.weight.data().toArray();
            for (int s = 0; s < shards; s++) {
                double[] slice = new double[outputSize * perShard];
                for (int o = 0; o < outputSize; o++) {
                    System.arraycopy(weights, o * inputSize + s * perShard, slice,
                            o * perShard, perShard);
                }
                this.weightShards[s] = autograd.variable(slice, Shape.of(outputSize, perShard));
            }
        }
    }

    @Override
    public Object forward(Object input) {
        Variable x = (Variable) input;
        // the reference single-device forward: column shards concatenate,
        // row partials sum (bias added once, not per shard)
        if (split == Split.COLUMN) {
            Variable output = shardedForward(x, 0);
            for (int shard = 1; shard < shards; shard++) {
                output = concat(output, shardedForward(x, shard));
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
        Shape outShape = Shape.of(shape[0] + right.shape().dimensions()[0], shape[1]);
        boolean reqGrad = left.requiresGradient() || right.requiresGradient();
        Variable output = reqGrad
                ? autograd.variable(joined, outShape)
                : autograd.constant(joined, outShape);
        int aLen = a.length;
        int bLen = b.length;
        return autograd.record(output, List.of(left, right), gradOutput -> new double[][]{
                Arrays.copyOfRange(gradOutput, 0, aLen),
                Arrays.copyOfRange(gradOutput, aLen, aLen + bLen)
        });
    }

    private Variable broadcastBias(Variable x) {
        int rows = (int) weight.shape().dimensions()[0];
        int cols = x.shape().rank() > 1 ? (int) x.shape().dimensions()[1] : 1;
        double[] values = new double[rows * cols];
        double[] biasValues = bias.data().toArray();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                values[r * cols + c] = biasValues[r];
            }
        }
        Variable expanded = bias.requiresGradient()
                ? autograd.variable(values, Shape.of(rows, cols))
                : autograd.constant(values, Shape.of(rows, cols));
        return autograd.record(expanded, List.of(bias), gradOutput -> {
            double[] gradBias = new double[rows];
            for (int r = 0; r < rows; r++) {
                double sum = 0;
                for (int c = 0; c < cols; c++) {
                    sum += gradOutput[r * cols + c];
                }
                gradBias[r] = sum;
            }
            return new double[][]{gradBias};
        });
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
        int cols = x.shape().rank() > 1 ? (int) x.shape().dimensions()[1] : 1;
        if (split == Split.COLUMN) {
            int perShard = outputs / shards;
            double[] slice = new double[perShard * layerInputs];
            System.arraycopy(weights, shard * perShard * layerInputs, slice, 0, slice.length);
            weightShards[shard].loadValues(slice);
            Variable sliceVariable = autograd.variable(slice, Shape.of(perShard, layerInputs));
            autograd.record(sliceVariable, List.of(weight, weightShards[shard]), gradOutput -> {
                double[] fullGrad = new double[outputs * layerInputs];
                System.arraycopy(gradOutput, 0, fullGrad, shard * perShard * layerInputs, gradOutput.length);
                return new double[][]{fullGrad, gradOutput.clone()};
            });
            Variable product = autograd.matmul(sliceVariable, x);
            return autograd.add(product, biasSlice(shard, perShard, cols));
        }
        int perShard = layerInputs / shards;
        double[] slice = new double[outputs * perShard];
        for (int o = 0; o < outputs; o++) {
            System.arraycopy(weights, o * layerInputs + shard * perShard, slice,
                    o * perShard, perShard);
        }
        weightShards[shard].loadValues(slice);
        Variable sliceVariable = autograd.variable(slice, Shape.of(outputs, perShard));
        autograd.record(sliceVariable, List.of(weight, weightShards[shard]), gradOutput -> {
            double[] fullGrad = new double[outputs * layerInputs];
            for (int o = 0; o < outputs; o++) {
                System.arraycopy(gradOutput, o * perShard, fullGrad,
                        o * layerInputs + shard * perShard, perShard);
            }
            return new double[][]{fullGrad, gradOutput.clone()};
        });
        double[] xSlice = Arrays.copyOfRange(inputs, shard * perShard * cols, (shard + 1) * perShard * cols);
        Variable xVariable = x.requiresGradient()
                ? autograd.variable(xSlice, Shape.of(perShard, cols))
                : autograd.constant(xSlice, Shape.of(perShard, cols));
        if (x.requiresGradient()) {
            autograd.record(xVariable, List.of(x), gradOutput -> {
                double[] fullXGrad = new double[layerInputs * cols];
                System.arraycopy(gradOutput, 0, fullXGrad, shard * perShard * cols, gradOutput.length);
                return new double[][]{fullXGrad};
            });
        }
        return autograd.matmul(sliceVariable, xVariable);
    }

    private Variable biasSlice(int shard, int perShard, int cols) {
        int outputs = (int) bias.shape().dimensions()[0];
        double[] biasValues = bias.data().toArray();
        double[] values = new double[perShard * cols];
        for (int r = 0; r < perShard; r++) {
            for (int c = 0; c < cols; c++) {
                values[r * cols + c] = biasValues[shard * perShard + r];
            }
        }
        Variable sliceVar = bias.requiresGradient()
                ? autograd.variable(values, Shape.of(perShard, cols))
                : autograd.constant(values, Shape.of(perShard, cols));
        return autograd.record(sliceVar, List.of(bias), gradOutput -> {
            double[] fullGrad = new double[outputs];
            for (int r = 0; r < perShard; r++) {
                double sum = 0;
                for (int c = 0; c < cols; c++) {
                    sum += gradOutput[r * cols + c];
                }
                fullGrad[shard * perShard + r] = sum;
            }
            return new double[][]{fullGrad};
        });
    }

    public Variable weight() {
        return weight;
    }

    public Variable bias() {
        return bias;
    }

    public Variable weightShard(int shard) {
        if (shard < 0 || shard >= shards) {
            throw new IllegalArgumentException("shard out of range");
        }
        return weightShards[shard];
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
