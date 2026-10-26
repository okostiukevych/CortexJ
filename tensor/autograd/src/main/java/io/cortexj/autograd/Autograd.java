package io.cortexj.autograd;

import io.cortexj.tensor.DType;
import io.cortexj.tensor.Device;
import io.cortexj.tensor.Shape;
import io.cortexj.tensor.Tensor;
import io.cortexj.tensor.TensorBackend;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
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
    private final List<TapeNode> tape = Collections.synchronizedList(new ArrayList<TapeNode>());
    private final ThreadLocal<Boolean> recording = ThreadLocal.withInitial(() -> Boolean.TRUE);

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
        Shape outShape = result.shape();
        Shape leftShape = left.shape();
        Shape rightShape = right.shape();
        Variable output = new Variable(result, needsGrad(left, right));
        record(output, List.of(left, right), (NodeBackward) gradOutput -> new double[][]{
                reduceBroadcast(gradOutput, outShape, leftShape),
                reduceBroadcast(gradOutput, outShape, rightShape)
        });
        return output;
    }

    public Variable mul(Variable left, Variable right) {
        Tensor result = backend.mul(left.data(), right.data());
        Shape outShape = result.shape();
        Shape leftShape = left.shape();
        Shape rightShape = right.shape();
        Variable output = new Variable(result, needsGrad(left, right));
        record(output, List.of(left, right), gradOutput -> {
            double[] leftData = expandBroadcast(left.data().toArray(), leftShape, outShape);
            double[] rightData = expandBroadcast(right.data().toArray(), rightShape, outShape);
            return new double[][]{
                    reduceBroadcast(elementwiseMul(gradOutput, rightData), outShape, leftShape),
                    reduceBroadcast(elementwiseMul(gradOutput, leftData), outShape, rightShape)
            };
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
        Variable output = new Variable(result, isRecording() && input.requiresGradient());
        record(output, List.of(input), gradOutput -> new double[][]{
                broadcast(gradOutput, input.shape())
        });
        return output;
    }

    public Variable mean(Variable input) {
        Tensor result = backend.mean(input.data());
        Variable output = new Variable(result, isRecording() && input.requiresGradient());
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
        Variable output = new Variable(result, isRecording() && input.requiresGradient());
        record(output, List.of(input), gradOutput -> {
            double[] softmaxValues = result.toArray();
            long[] dims = input.shape().dimensions();
            int cols = (int) dims[dims.length - 1];
            int rows = softmaxValues.length / cols;
            double[] gradient = new double[gradOutput.length];
            for (int r = 0; r < rows; r++) {
                int offset = r * cols;
                double weightedSum = 0;
                for (int c = 0; c < cols; c++) {
                    weightedSum += gradOutput[offset + c] * softmaxValues[offset + c];
                }
                for (int c = 0; c < cols; c++) {
                    double s = softmaxValues[offset + c];
                    gradient[offset + c] = s * (gradOutput[offset + c] - weightedSum);
                }
            }
            return new double[][]{gradient};
        });
        return output;
    }

    public Variable relu(Variable input) {
        Tensor result = backend.relu(input.data());
        Variable output = new Variable(result, isRecording() && input.requiresGradient());
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
        if (!output.requiresGradient() || !isRecording()) {
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
        IdentityHashMap<Variable, TapeNode> producers = new IdentityHashMap<Variable, TapeNode>();
        synchronized (tape) {
            for (TapeNode node : tape) {
                producers.put(node.output(), node);
            }
        }
        List<TapeNode> postOrder = new ArrayList<TapeNode>();
        Set<Variable> visited = Collections.newSetFromMap(new IdentityHashMap<Variable, Boolean>());
        buildTopo(loss, producers, visited, postOrder);
        Collections.reverse(postOrder);
        for (TapeNode node : postOrder) {
            node.output().clearGradient();
        }
        double[] ones = new double[1];
        ones[0] = 1.0;
        loss.seedGradient(ones);
        for (TapeNode node : postOrder) {
            node.applyBackward(node.output().gradInternal());
        }
    }

    private void buildTopo(Variable output,
                           IdentityHashMap<Variable, TapeNode> producers,
                           Set<Variable> visited,
                           List<TapeNode> postOrder) {
        if (!visited.add(output)) {
            return;
        }
        TapeNode node = producers.get(output);
        if (node != null) {
            for (Variable input : node.inputs()) {
                buildTopo(input, producers, visited, postOrder);
            }
            postOrder.add(node);
        }
    }

    public Variable detach(Variable input) {
        return input.detach();
    }

    /**
     * No-grad scope: ops inside the supplier skip recording (ADR-026).
     */
    public <T> T noGrad(Supplier<T> body) {
        boolean previous = isRecording();
        recording.set(Boolean.FALSE);
        try {
            return body.get();
        } finally {
            recording.set(Boolean.valueOf(previous));
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

    List<TapeNode> tapeNodes() {
        return tape;
    }

    private boolean isRecording() {
        return Boolean.TRUE.equals(recording.get());
    }

    private boolean needsGrad(Variable left, Variable right) {
        // no-grad scope: outputs of unrecorded ops do not require gradient
        return isRecording() && (left.requiresGradient() || right.requiresGradient());
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

    private static double[] expandBroadcast(double[] values, Shape inShape, Shape outShape) {
        if (inShape.equals(outShape)) {
            return values;
        }
        long[] outDims = outShape.dimensions();
        long[] inDims = inShape.dimensions();
        long[] outStrides = stridesOf(outDims);
        long[] inStrides = stridesOf(inDims);
        int outSize = (int) outShape.size();
        double[] expanded = new double[outSize];
        for (int flat = 0; flat < outSize; flat++) {
            expanded[flat] = values[(int) broadcastOffset(flat, outDims, outStrides, inDims, inStrides)];
        }
        return expanded;
    }

    private static double[] reduceBroadcast(double[] gradOutput, Shape outShape, Shape targetShape) {
        if (outShape.equals(targetShape)) {
            return gradOutput.clone();
        }
        if (gradOutput.length == 1) {
            double[] filled = new double[(int) targetShape.size()];
            java.util.Arrays.fill(filled, gradOutput[0]);
            return filled;
        }
        long[] outDims = outShape.dimensions();
        long[] inDims = targetShape.dimensions();
        long[] outStrides = stridesOf(outDims);
        long[] inStrides = stridesOf(inDims);
        double[] reduced = new double[(int) targetShape.size()];
        for (int flat = 0; flat < gradOutput.length; flat++) {
            int idx = (int) broadcastOffset(flat, outDims, outStrides, inDims, inStrides);
            reduced[idx] += gradOutput[flat];
        }
        return reduced;
    }

    private static long[] stridesOf(long[] dims) {
        long[] strides = new long[dims.length];
        long stride = 1;
        for (int i = dims.length - 1; i >= 0; i--) {
            strides[i] = stride;
            stride *= dims[i];
        }
        return strides;
    }

    private static long broadcastOffset(long flat, long[] outShape, long[] outStrides,
                                        long[] inShape, long[] inStrides) {
        long offset = 0;
        int shift = outShape.length - inShape.length;
        for (int i = inShape.length - 1; i >= 0; i--) {
            int outAxis = i + shift;
            long coordinate = (flat / outStrides[outAxis]) % outShape[outAxis];
            long inCoordinate = inShape[i] == 1 ? 0 : coordinate;
            offset += inCoordinate * inStrides[i];
        }
        return offset;
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
