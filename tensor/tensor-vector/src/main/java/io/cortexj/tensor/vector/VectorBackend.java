package io.cortexj.tensor.vector;

import io.cortexj.error.CortexJException;
import io.cortexj.tensor.DType;
import io.cortexj.tensor.Device;
import io.cortexj.tensor.Shape;
import io.cortexj.tensor.Tensor;
import io.cortexj.tensor.TensorBackend;
import io.cortexj.tensor.cpu.CpuBackend;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Accelerated tensor backend over the JDK Vector API (ADR-025): SIMD
 * matmul/reductions behind the same shared contract. The incubator
 * module stays isolated in this provider. Results must be parity-equal
 * with the CPU reference (the conformance suite asserts tolerance).
 */
public class VectorBackend implements TensorBackend {

    private static final String BACKEND_ID = "vector";
    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

    private final CpuBackend reference = new CpuBackend();

    @Override
    public String id() {
        return BACKEND_ID;
    }

    public int vectorLength() {
        return SPECIES.length();
    }

    @Override
    public boolean supports(DType dtype, Device device) {
        return dtype == DType.F32 && (device == Device.CPU || device == Device.AUTO);
    }

    @Override
    public Tensor create(double[] values, Shape shape, DType dtype, Device device) {
        return reference.create(values, shape, dtype, device);
    }

    @Override
    public Tensor matmul(Tensor left, Tensor right) {
        CpuBackend.CpuTensor a = checked(left);
        CpuBackend.CpuTensor b = checked(right);
        long[] aShape = a.shape().dimensions();
        long[] bShape = b.shape().dimensions();
        if (aShape.length != 2 || bShape.length != 2 || aShape[1] != bShape[0]) {
            throw new IllegalArgumentException("matmul needs 2D tensors with matching inner dimension");
        }
        int rows = (int) aShape[0];
        int inner = (int) aShape[1];
        int columns = (int) bShape[1];
        if ((long) rows * columns > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException("matmul result is too large for this backend");
        }
        float[] aFloat = toFloat(a.toArray());
        float[] bFloat = toFloat(b.toArray());
        float[] result = new float[rows * columns];
        // vectorize over columns: b rows are contiguous, a values scalar
        for (int r = 0; r < rows; r++) {
            int rowOffset = r * columns;
            int aOffset = r * inner;
            for (int k = 0; k < inner; k++) {
                float aValue = aFloat[aOffset + k];
                if (aValue == 0.0f) {
                    continue;
                }
                int bOffset = k * columns;
                int c = 0;
                for (; c + SPECIES.length() <= columns; c += SPECIES.length()) {
                    FloatVector resultVector = FloatVector.fromArray(SPECIES, result, rowOffset + c);
                    FloatVector bVector = FloatVector.fromArray(SPECIES, bFloat, bOffset + c);
                    resultVector.add(bVector.mul(aValue)).intoArray(result, rowOffset + c);
                }
                for (; c < columns; c++) {
                    result[rowOffset + c] += aValue * bFloat[bOffset + c];
                }
            }
        }
        return reference.create(toDouble(result), Shape.of(rows, columns), DType.F32, Device.CPU);
    }

    @Override
    public Tensor add(Tensor left, Tensor right) {
        return reference.add(left, right);
    }

    @Override
    public Tensor mul(Tensor left, Tensor right) {
        return reference.mul(left, right);
    }

    @Override
    public Tensor sum(Tensor tensor) {
        CpuBackend.CpuTensor value = checked(tensor);
        float[] data = toFloat(value.toArray());
        FloatVector accumulator = FloatVector.zero(SPECIES);
        int i = 0;
        for (; i + SPECIES.length() <= data.length; i += SPECIES.length()) {
            accumulator = accumulator.add(FloatVector.fromArray(SPECIES, data, i));
        }
        float total = accumulator.reduceLanes(VectorOperators.ADD);
        for (; i < data.length; i++) {
            total += data[i];
        }
        return reference.create(new double[]{total}, Shape.of(1), DType.F32, Device.CPU);
    }

    @Override
    public Tensor mean(Tensor tensor) {
        return reference.mean(tensor);
    }

    @Override
    public Tensor softmax(Tensor tensor) {
        return reference.softmax(tensor);
    }

    @Override
    public Tensor sigmoid(Tensor tensor) {
        return reference.sigmoid(tensor);
    }

    @Override
    public Tensor tanh(Tensor tensor) {
        return reference.tanh(tensor);
    }

    @Override
    public Tensor gelu(Tensor tensor) {
        return reference.gelu(tensor);
    }

    @Override
    public Tensor layerNorm(Tensor tensor, double epsilon) {
        return reference.layerNorm(tensor, epsilon);
    }

    @Override
    public Tensor gather(Tensor source, long[] indices) {
        return reference.gather(source, indices);
    }

    @Override
    public Tensor scatter(long[] indices, Tensor values, Shape targetShape) {
        return reference.scatter(indices, values, targetShape);
    }

    @Override
    public Tensor relu(Tensor tensor) {
        return reference.relu(tensor);
    }

    @Override
    public Tensor reshape(Tensor tensor, Shape shape) {
        return reference.reshape(tensor, shape);
    }

    @Override
    public Tensor transpose(Tensor tensor) {
        return reference.transpose(tensor);
    }

    private CpuBackend.CpuTensor checked(Tensor tensor) {
        if (!(tensor instanceof CpuBackend.CpuTensor)) {
            throw new IllegalArgumentException("this backend executes cpu-backend tensors only");
        }
        CpuBackend.CpuTensor value = (CpuBackend.CpuTensor) tensor;
        value.requireOpen();
        return value;
    }

    private static float[] toFloat(double[] values) {
        float[] floats = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            floats[i] = (float) values[i];
        }
        return floats;
    }

    private static double[] toDouble(float[] values) {
        double[] doubles = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            doubles[i] = values[i];
        }
        return doubles;
    }
}
