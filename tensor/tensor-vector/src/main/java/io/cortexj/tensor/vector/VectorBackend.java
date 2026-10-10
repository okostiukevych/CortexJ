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
        int lanes = SPECIES.length();
        int vectorColumns = (columns / lanes) * lanes;
        int rowBlock = 4;
        // register-accumulator micro-kernel: 4 rows x 1 c-block per pass;
        // b vectors are loaded once per (rowBlock, c-block, k) instead of
        // once per row
        for (int rBase = 0; rBase < rows; rBase += rowBlock) {
            int blockRows = Math.min(rowBlock, rows - rBase);
            for (int c = 0; c < vectorColumns; c += lanes) {
                FloatVector acc0 = FloatVector.zero(SPECIES);
                FloatVector acc1 = FloatVector.zero(SPECIES);
                FloatVector acc2 = FloatVector.zero(SPECIES);
                FloatVector acc3 = FloatVector.zero(SPECIES);
                for (int k = 0; k < inner; k++) {
                    FloatVector bVector = FloatVector.fromArray(SPECIES, bFloat, k * columns + c);
                    acc0 = acc0.add(bVector.mul(aFloat[(rBase) * inner + k]));
                    if (blockRows > 1) {
                        acc1 = acc1.add(bVector.mul(aFloat[(rBase + 1) * inner + k]));
                    }
                    if (blockRows > 2) {
                        acc2 = acc2.add(bVector.mul(aFloat[(rBase + 2) * inner + k]));
                    }
                    if (blockRows > 3) {
                        acc3 = acc3.add(bVector.mul(aFloat[(rBase + 3) * inner + k]));
                    }
                }
                acc0.intoArray(result, rBase * columns + c);
                if (blockRows > 1) {
                    acc1.intoArray(result, (rBase + 1) * columns + c);
                }
                if (blockRows > 2) {
                    acc2.intoArray(result, (rBase + 2) * columns + c);
                }
                if (blockRows > 3) {
                    acc3.intoArray(result, (rBase + 3) * columns + c);
                }
            }
            for (int r = rBase; r < rBase + blockRows; r++) {
                for (int c = vectorColumns; c < columns; c++) {
                    float total = 0;
                    for (int k = 0; k < inner; k++) {
                        total += aFloat[r * inner + k] * bFloat[k * columns + c];
                    }
                    result[r * columns + c] = total;
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
    public Tensor zeros(Shape shape, DType dtype, Device device) {
        return reference.zeros(shape, dtype, device);
    }

    @Override
    public Tensor fill(Shape shape, double value, DType dtype, Device device) {
        return reference.fill(shape, value, dtype, device);
    }

    @Override
    public Tensor random(Shape shape, DType dtype, Device device, long seed) {
        return reference.random(shape, dtype, device, seed);
    }

    @Override
    public Tensor batchMatmul(Tensor left, Tensor right) {
        return reference.batchMatmul(left, right);
    }

    @Override
    public Tensor logSoftmax(Tensor tensor) {
        return reference.logSoftmax(tensor);
    }

    @Override
    public Tensor slice(Tensor tensor, long from, long to) {
        return reference.slice(tensor, from, to);
    }

    @Override
    public Object unwrap(Tensor tensor) {
        return reference.unwrap(tensor);
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
