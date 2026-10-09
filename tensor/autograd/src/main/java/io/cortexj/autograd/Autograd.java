package io.cortexj.autograd;

import io.cortexj.tensor.DType;
import io.cortexj.tensor.Device;
import io.cortexj.tensor.Shape;
import io.cortexj.tensor.Tensor;
import io.cortexj.tensor.TensorBackend;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Tape-based autograd over the Tensor API (ADR-026). Tracked ops append
 * nodes at forward time; {@link #backward} walks the tape in reverse
 * and accumulates gradients into leaf variables. {@link #noGrad} skips
 * recording; custom rules use {@link #record}. One graph per Autograd
 * instance.
 */
public final class Autograd {

    private final TensorBackend backend;
    private final List<TapeNode> tape = new ArrayList<TapeNode>();
    private boolean recording = true;

    public Autograd(TensorBackend backend) {
        if (backend == null) {
            throw new IllegalArgumentException("backend must not be null");
        }
        this.backend = backend;
    }

    public Variable variable(double[] values, Shape shape) {
        return Variable.parameter(
                backend.create(values, shape, DType.F32, Device.CPU));
    }

    public Variable constant(double[] values, Shape shape) {
        return Variable.constant(
                backend.create(values, shape, DType.F32, Device.CPU));
    }

    public Variable add(Variable left, Variable right) {
        Tensor result = backend.add(left.data(), right.data());
        Variable output = new Variable(result, needsGrad(left, right));
        record(output, List.of(left, right), (NodeBackward) gradOutput -> new double[][]{
                broadcast(gradOutput, left.shape()),
                broadcast(gradOutput, right.shape())
        });
        return output;
    }

    public Variable mul(Variable left, Variable right) {
        Tensor result = backend.mul(left.data(), right.data());
        Variable output = new Variable(result, needsGrad(left, right));
        record(output, List.of(left, right), gradOutput -> new double[][]{
                elementwiseMul(gradOutput, right.data().toArray()),
                elementwiseMul(gradOutput, left.data().toArray())
        });
        return output;
    }

    public Variable matmul(Variable left, Variable right) {
        Tensor result = backend.matmul(left.data(), right.data());
        Variable output = new Variable(result, needsGrad(left, right));
        record(output, List.of(left, right), gradOutput -> new double[][]{
                // dA = grad · B^T, dB = A^T · grad
                backend.matmul(tensorOf(gradOutput, output.shape()),
                        backend.transpose(right.data())).toArray(),
                backend.matmul(backend.transpose(left.data()),
                        tensorOf(gradOutput, output.shape())).toArray()
        });
        return output;
    }

    public Variable sum(Variable input) {
        Tensor result = backend.sum(input.data());
        Variable output = new Variable(result, input.requiresGradient());
        record(output, List.of(input), gradOutput -> new double[][]{
                broadcast(gradOutput, input.shape())
        });
        return output;
    }

    public Variable mean(Variable input) {
        Tensor result = backend.mean(input.data());
        Variable output = new Variable(result, input.requiresGradient());
        double scale = 1.0 / input.shape().size();
        record(output, List.of(input), gradOutput -> {
            double[] broadcast = broadcast(gradOutput, input.shape());
            for (int i = 0; i < broadcast.length; i++) {
                broadcast[i] *= scale;
            }
            return new double[][]{broadcast};
        });
        return output;
    }

    public Variable softmax(Variable input) {
        Tensor result = backend.softmax(input.data());
        Variable output = new Variable(result, input.requiresGradient());
        record(output, List.of(input), gradOutput -> {
            double[] softmaxValues = result.toArray();
            double[] weighted = elementwiseMul(gradOutput, softmaxValues);
            double weightedSum = 0;
            for (double value : weighted) {
                weightedSum += value;
            }
            double[] gradient = new double[gradOutput.length];
            for (int i = 0; i < gradient.length; i++) {
                gradient[i] = weighted[i] - softmaxValues[i] * weightedSum;
            }
            return new double[][]{gradient};
        });
        return output;
    }

    public Variable relu(Variable input) {
        Tensor result = backend.relu(input.data());
        Variable output = new Variable(result, input.requiresGradient());
        record(output, List.of(input), gradOutput -> {
            double[] dataValues = input.data().toArray();
            double[] gradient = new double[gradOutput.length];
            for (int i = 0; i < gradient.length; i++) {
                gradient[i] = dataValues[i] > 0 ? gradOutput[i] : 0;
            }
            return new double[][]{gradient};
        });
        return output;
    }

    /**
     * Custom op registration: the caller owns the forward result and the
     * backward rule; the tape handles accumulation (ADR-026).
     */
    public Variable record(Variable output, List<Variable> inputs, TapeNode.Backward backward) {
        if (!output.requiresGradient() || !recording) {
            return output;
        }
        tape.add(new TapeNode(output, inputs, backward));
        return output;
    }

    /**
     * Backward from a scalar loss: seeds ones, walks only the nodes that
     * contribute to this loss (reverse topological order) and accumulates
     * into leaf variables. A second backward on the same tape replays
     * only its own subgraph (ADR-026).
     */
    public void backward(Variable loss) {
        if (loss.shape().size() != 1) {
            throw new IllegalArgumentException("backward needs a scalar loss");
        }
        if (!loss.requiresGradient()) {
            throw new IllegalArgumentException("the loss does not require gradient");
        }
        double[] ones = new double[1];
        ones[0] = 1.0;
        loss.seedGradient(ones);
        // collectReachable returns parents-before-children: consumers run
        // before producers, which is exactly the valid backward order
        List<TapeNode> reachable = new ArrayList<TapeNode>();
        collectReachable(loss, reachable, new java.util.HashSet<Variable>());
        for (TapeNode node : reachable) {
            node.applyBackward(node.output().gradInternal());
        }
    }

    private void collectReachable(Variable output, List<TapeNode> into, java.util.HashSet<Variable> visited) {
        if (!visited.add(output)) {
            return;
        }
        for (TapeNode node : tape) {
            if (node.output() == output) {
                into.add(node);
                for (Variable input : node.inputs()) {
                    collectReachable(input, into, visited);
                }
            }
        }
    }

    public Variable detach(Variable input) {
        return input.detach();
    }

    /**
     * No-grad scope: ops inside the supplier skip recording (ADR-026).
     */
    public <T> T noGrad(Supplier<T> body) {
        boolean previous = recording;
        recording = false;
        try {
            return body.get();
        } finally {
            recording = previous;
        }
    }

    /**
     * Drops the tape without running backward (retention policy, ADR-026).
     */
    public void clearTape() {
        tape.clear();
    }

    public int tapeSize() {
        return tape.size();
    }

    private boolean needsGrad(Variable left, Variable right) {
        // no-grad scope: outputs of unrecorded ops do not require gradient
        return recording && (left.requiresGradient() || right.requiresGradient());
    }

    private double[] broadcast(double[] source, Shape shape) {
        long size = shape.size();
        double[] broadcast = new double[(int) size];
        if (source.length == size) {
            System.arraycopy(source, 0, broadcast, 0, source.length);
            return broadcast;
        }
        if (source.length == 1) {
            java.util.Arrays.fill(broadcast, source[0]);
            return broadcast;
        }
        throw new IllegalArgumentException("cannot broadcast gradient of length " + source.length
                + " to " + shape);
    }

    private static double[] elementwiseMul(double[] left, double[] right) {
        double[] result = new double[left.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = left[i] * right[i];
        }
        return result;
    }

    private Tensor tensorOf(double[] values, Shape shape) {
        return backend.create(values, shape, DType.F32, Device.CPU);
    }

    private interface NodeBackward extends TapeNode.Backward {
    }
}
