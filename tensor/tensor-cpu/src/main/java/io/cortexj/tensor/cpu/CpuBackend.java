package io.cortexj.tensor.cpu;

import io.cortexj.error.CortexJException;
import io.cortexj.tensor.DType;
import io.cortexj.tensor.Device;
import io.cortexj.tensor.Shape;
import io.cortexj.tensor.Tensor;
import io.cortexj.tensor.TensorBackend;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scalar reference tensor backend (ADR-025): simple loops, correctness
 * first. Results own their storage; views share the parent storage and
 * closing a view never frees shared data (ADR-024).
 */
public class CpuBackend implements TensorBackend {

    private static final String BACKEND_ID = "cpu";

    @Override
    public String id() {
        return BACKEND_ID;
    }

    @Override
    public boolean supports(DType dtype, Device device) {
        return dtype == DType.F32 && (device == Device.CPU || device == Device.AUTO);
    }

    @Override
    public Tensor create(double[] values, Shape shape, DType dtype, Device device) {
        if (values == null || values.length != shape.size()) {
            throw new IllegalArgumentException("value count " + (values == null ? 0 : values.length)
                    + " does not match shape size " + shape.size());
        }
        return new CpuTensor(shape, dtype, values.clone(), null);
    }

    @Override
    public Tensor matmul(Tensor left, Tensor right) {
        CpuTensor a = checked(left);
        CpuTensor b = checked(right);
        long[] aShape = a.shape().dimensions();
        long[] bShape = b.shape().dimensions();
        if (aShape.length != 2 || bShape.length != 2 || aShape[1] != bShape[0]) {
            throw new IllegalArgumentException("matmul needs 2D tensors with matching inner dimension");
        }
        int rows = (int) aShape[0];
        int inner = (int) aShape[1];
        int columns = (int) bShape[1];
        if ((long) rows * columns > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException("matmul result is too large for the reference backend");
        }
        double[] result = new double[rows * columns];
        double[] bData = b.toArray();
        for (int r = 0; r < rows; r++) {
            for (int k = 0; k < inner; k++) {
                double aValue = a.get(r, k);
                if (aValue == 0.0) {
                    continue;
                }
                for (int c = 0; c < columns; c++) {
                    result[r * columns + c] += aValue * bData[k * columns + c];
                }
            }
        }
        return new CpuTensor(Shape.of(rows, columns), a.dtype(), result, null);
    }

    @Override
    public Tensor add(Tensor left, Tensor right) {
        return elementwise(checked(left), checked(right), Double::sum);
    }

    @Override
    public Tensor mul(Tensor left, Tensor right) {
        return elementwise(checked(left), checked(right), (x, y) -> x * y);
    }

    @Override
    public Tensor sum(Tensor tensor) {
        CpuTensor value = checked(tensor);
        double total = 0;
        for (double element : value.toArray()) {
            total += element;
        }
        return new CpuTensor(Shape.of(1), value.dtype(), new double[]{total}, null);
    }

    @Override
    public Tensor mean(Tensor tensor) {
        CpuTensor value = checked(tensor);
        Tensor total = sum(value);
        return new CpuTensor(Shape.of(1), value.dtype(),
                new double[]{total.get(0) / value.shape().size()}, null);
    }

    @Override
    public Tensor softmax(Tensor tensor) {
        CpuTensor value = checked(tensor);
        double[] data = value.toArray();
        double max = Double.NEGATIVE_INFINITY;
        for (double element : data) {
            max = Math.max(max, element);
        }
        double total = 0;
        double[] result = new double[data.length];
        for (int i = 0; i < data.length; i++) {
            result[i] = Math.exp(data[i] - max);
            total += result[i];
        }
        for (int i = 0; i < result.length; i++) {
            result[i] /= total;
        }
        return new CpuTensor(value.shape(), value.dtype(), result, null);
    }

    @Override
    public Tensor relu(Tensor tensor) {
        return elementwise(checked(tensor), null, Math::max);
    }

    private Tensor elementwise(CpuTensor left, CpuTensor right,
                               java.util.function.DoubleBinaryOperator op) {
        double[] a = left.toArray();
        double[] b = right == null ? null : right.toArray();
        if (right != null && !left.shape().equals(right.shape())) {
            throw new IllegalArgumentException("elementwise ops need equal shapes");
        }
        double[] result = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            result[i] = op.applyAsDouble(a[i], right == null ? 0.0 : b[i]);
        }
        return new CpuTensor(left.shape(), left.dtype(), result, null);
    }

    @Override
    public Tensor reshape(Tensor tensor, Shape shape) {
        CpuTensor value = checked(tensor);
        if (value.shape().size() != shape.size()) {
            throw new IllegalArgumentException("reshape needs the same element count");
        }
        return new CpuTensor(shape, value.dtype(), value.toArray(), null);
    }

    @Override
    public Tensor transpose(Tensor tensor) {
        CpuTensor value = checked(tensor);
        long[] shape = value.shape().dimensions();
        if (shape.length != 2) {
            throw new IllegalArgumentException("transpose needs a 2D tensor");
        }
        int rows = (int) shape[0];
        int columns = (int) shape[1];
        double[] result = new double[rows * columns];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                result[c * rows + r] = value.get(r, c);
            }
        }
        return new CpuTensor(Shape.of(columns, rows), value.dtype(), result, null);
    }

    private CpuTensor checked(Tensor tensor) {
        if (!(tensor instanceof CpuTensor)) {
            throw new IllegalArgumentException("the cpu backend executes cpu tensors only");
        }
        CpuTensor value = (CpuTensor) tensor;
        value.requireOpen();
        return value;
    }

    /**
     * The reference tensor implementation (public so accelerated backends
     * share it behind the same API). Results own their storage; views
     * share the parent storage and closing a view never frees shared
     * data (ADR-024).
     */
    public static final class CpuTensor implements Tensor {

        private final Shape shape;
        private final DType dtype;
        private final double[] storage;
        private final double[] sharedWith;
        private final AtomicBoolean closed = new AtomicBoolean();

        public CpuTensor(Shape shape, DType dtype, double[] storage, double[] sharedWith) {
            this.shape = shape;
            this.dtype = dtype;
            this.storage = storage;
            this.sharedWith = sharedWith;
        }

        @Override
        public Shape shape() {
            return shape;
        }

        @Override
        public DType dtype() {
            return dtype;
        }

        @Override
        public Device device() {
            return Device.CPU;
        }

        @Override
        public double get(long... index) {
            requireOpen();
            return storage[(int) shape.offsetOf(index)];
        }

        @Override
        public double[] toArray() {
            requireOpen();
            return storage.clone();
        }

        @Override
        public boolean isView() {
            return sharedWith != null;
        }

        @Override
        public Tensor to(Device device) {
            requireOpen();
            if (device == Device.CPU || device == Device.AUTO) {
                return this;
            }
            CortexJException failure = new CortexJException("CORTEXJ-TENSOR-0003",
                    "the cpu reference backend cannot transfer to " + device,
                    "Add an accelerated backend module for " + device + ".");
            failure.detail("backend", BACKEND_ID);
            throw failure;
        }

        @Override
        public void setAll(double[] values) {
            requireOpen();
            if (values == null || values.length != storage.length) {
                throw new IllegalArgumentException("value count "
                        + (values == null ? 0 : values.length)
                        + " does not match tensor size " + storage.length);
            }
            System.arraycopy(values, 0, storage, 0, values.length);
        }

        public void requireOpen() {
            if (closed.get()) {
                throw new CortexJException("CORTEXJ-TENSOR-0004",
                        "tensor is closed", "Recreate the tensor.");
            }
        }
        @Override
        public void close() {
            // GC-managed storage: close releases the reference; a view's
            // close never frees the shared parent data (ADR-024)
            closed.set(true);
        }
    }
}
