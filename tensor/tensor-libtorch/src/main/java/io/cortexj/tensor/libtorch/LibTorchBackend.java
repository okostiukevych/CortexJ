package io.cortexj.tensor.libtorch;

import io.cortexj.error.CortexJException;
import io.cortexj.tensor.DType;
import io.cortexj.tensor.Device;
import io.cortexj.tensor.Shape;
import io.cortexj.tensor.Tensor;
import io.cortexj.tensor.TensorBackend;
import io.cortexj.tensor.cpu.CpuBackend;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * LibTorch tensor backend over the versioned C ABI shim (ADR-023):
 * explicit op allowlist (add/mul/matmul/relu on F32 CPU), differential
 * tests against the CPU reference, typed failures for everything else.
 * The shim path comes from configuration only (allowlisted); the Java
 * side refuses a mismatched shim ABI version typed.
 */
public class LibTorchBackend implements TensorBackend {

    public static final String BACKEND_ID = "libtorch";

    /** Pinned shim ABI version; anything else fails typed. */
    public static final int SHIM_ABI_VERSION = 1;

    /** Configuration key for the shim library path. */
    public static final String CONFIG_SHIM_PATH = "libtorch.shimPath";

    /** Environment variable fallback for the shim library path. */
    public static final String ENV_SHIM_PATH = "CORTEXJ_LIBTORCH_SHIM";

    private static final Set<String> SUPPORTED_OPS;

    static {
        Set<String> ops = new LinkedHashSet<String>();
        ops.add("add");
        ops.add("mul");
        ops.add("matmul");
        ops.add("relu");
        SUPPORTED_OPS = Collections.unmodifiableSet(ops);
    }

    private final CpuBackend reference = new CpuBackend();
    private final NativeBridge bridge;

    public LibTorchBackend(java.util.Map<String, String> configuration) {
        String path = null;
        if (configuration != null) {
            path = configuration.get(CONFIG_SHIM_PATH);
        }
        if (path == null || path.trim().isEmpty()) {
            path = System.getenv(ENV_SHIM_PATH);
        }
        if (path == null || path.trim().isEmpty()) {
            throw nativeError("no shim library path configured",
                    "Set '" + CONFIG_SHIM_PATH + "' or " + ENV_SHIM_PATH
                            + " to the built torch_shim library.");
        }
        this.bridge = NativeBridge.load(Path.of(path.trim()));
    }

    public static Set<String> supportedOps() {
        return SUPPORTED_OPS;
    }

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
        return reference.create(values, shape, dtype, device);
    }

    @Override
    public Tensor matmul(Tensor left, Tensor right) {
        return binary("matmul", left, right);
    }

    @Override
    public Tensor add(Tensor left, Tensor right) {
        return binary("add", left, right);
    }

    @Override
    public Tensor mul(Tensor left, Tensor right) {
        return binary("mul", left, right);
    }

    @Override
    public Tensor sum(Tensor tensor) {
        return reference.sum(tensor);
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
    public Tensor relu(Tensor tensor) {
        CpuBackend.CpuTensor value = checked(tensor);
        long[] shape = value.shape().dimensions();
        if (shape.length != 2) {
            throw new IllegalArgumentException("libtorch relu needs a 2D tensor in 0.x");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment handle = bridge.create(
                    arena, toFloat(value.toArray()), shape[0], shape[1]);
            MemorySegment result = bridge.relu(handle);
            float[] data = bridge.read(result, value.shape().size());
            bridge.destroy(result);
            bridge.destroy(handle);
            double[] doubles = new double[data.length];
            for (int i = 0; i < data.length; i++) {
                doubles[i] = data[i];
            }
            return reference.create(doubles, value.shape(), DType.F32, Device.CPU);
        } catch (CortexJException e) {
            throw e;
        } catch (Throwable e) {
            throw nativeError("libtorch relu failed: " + e, e);
        }
    }

    @Override
    public Tensor reshape(Tensor tensor, Shape shape) {
        return reference.reshape(tensor, shape);
    }

    @Override
    public Tensor transpose(Tensor tensor) {
        return reference.transpose(tensor);
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
    public Tensor slice(Tensor tensor, long from, long to) {
        return reference.slice(tensor, from, to);
    }

    @Override
    public Object unwrap(Tensor tensor) {
        return reference.unwrap(tensor);
    }

    private Tensor binary(String op, Tensor left, Tensor right) {
        if (!SUPPORTED_OPS.contains(op)) {
            throw nativeError("op '" + op + "' is not in the libtorch allowlist",
                    "Use one of " + SUPPORTED_OPS + " or the reference backend.");
        }
        CpuBackend.CpuTensor a = checked(left);
        CpuBackend.CpuTensor b = checked(right);
        long[] aShape = a.shape().dimensions();
        long[] bShape = b.shape().dimensions();
        if (aShape.length != 2 || bShape.length != 2) {
            throw new IllegalArgumentException("libtorch native ops need 2D tensors in 0.x");
        }
        if ("matmul".equals(op) && aShape[1] != bShape[0]) {
            throw new IllegalArgumentException("matmul needs matching inner dimensions");
        }
        if (!"matmul".equals(op) && !a.shape().equals(b.shape())) {
            throw new IllegalArgumentException("elementwise libtorch ops need equal shapes");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment aHandle = bridge.create(
                    arena, toFloat(a.toArray()), aShape[0], aShape[1]);
            MemorySegment bHandle = bridge.create(
                    arena, toFloat(b.toArray()), bShape[0], bShape[1]);
            try {
                MemorySegment resultHandle = "add".equals(op) ? bridge.add(aHandle, bHandle)
                        : "mul".equals(op) ? bridge.mul(aHandle, bHandle)
                        : bridge.matmul(aHandle, bHandle);
                long[] outShape = "matmul".equals(op)
                        ? new long[]{aShape[0], bShape[1]}
                        : aShape;
                float[] data = bridge.read(resultHandle, outShape[0] * outShape[1]);
                bridge.destroy(resultHandle);
                double[] doubles = new double[data.length];
                for (int i = 0; i < data.length; i++) {
                    doubles[i] = data[i];
                }
                Shape shape = outShape.length == 2
                        ? Shape.of(outShape[0], outShape[1])
                        : Shape.of(outShape);
                return reference.create(doubles, shape, DType.F32, Device.CPU);
            } finally {
                bridge.destroy(aHandle);
                bridge.destroy(bHandle);
            }
        } catch (CortexJException e) {
            throw e;
        } catch (Throwable e) {
            throw nativeError("libtorch " + op + " failed: " + e, e);
        }
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

    private static CortexJException nativeError(String message, String remediation) {
        CortexJException failure = new CortexJException("CORTEXJ-TENSOR-0005", message, remediation);
        failure.detail("backend", BACKEND_ID);
        return failure;
    }

    private static CortexJException nativeError(String message, Throwable cause) {
        CortexJException failure = new CortexJException("CORTEXJ-TENSOR-0005", message, cause);
        failure.remediate("Check the shim path, the ABI version and the native logs.");
        failure.detail("backend", BACKEND_ID);
        return failure;
    }

    /**
     * The FFM bridge: loads the shim once, pins the ABI version, holds
     * the downcall handles. Native memory (tensor handles) is released
     * explicitly; arenas scope the float buffers.
     */
    static final class NativeBridge {

        private final MethodHandle create;
        private final MethodHandle add;
        private final MethodHandle mul;
        private final MethodHandle matmul;
        private final MethodHandle relu;
        private final MethodHandle read;
        private final MethodHandle destroy;

        private NativeBridge(MethodHandle create, MethodHandle add, MethodHandle mul,
                             MethodHandle matmul, MethodHandle relu, MethodHandle read,
                             MethodHandle destroy) {
            this.create = create;
            this.add = add;
            this.mul = mul;
            this.matmul = matmul;
            this.relu = relu;
            this.read = read;
            this.destroy = destroy;
        }

        static NativeBridge load(Path shim) {
            Linker linker = Linker.nativeLinker();
            SymbolLookup lookup;
            try {
                lookup = SymbolLookup.libraryLookup(shim, Arena.global());
            } catch (Throwable e) {
                throw nativeError("cannot load the libtorch shim: " + shim,
                        "Build torch_shim.cpp per tensor/tensor-libtorch/src/main/native"
                                + " and point '" + CONFIG_SHIM_PATH + "' at it.");
            }
            try {
                MethodHandle version = linker.downcallHandle(
                        lookup.find("spike_abi_version").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT));
                int abi = (int) version.invokeExact();
                if (abi != SHIM_ABI_VERSION) {
                    throw nativeError("shim ABI version " + abi + " does not match "
                            + SHIM_ABI_VERSION, "Rebuild the shim from the pinned source.");
                }
                return new NativeBridge(
                        downcall(linker, lookup, "spike_tensor_create",
                                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)),
                        downcall(linker, lookup, "spike_tensor_add",
                                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                        ValueLayout.ADDRESS)),
                        downcall(linker, lookup, "spike_tensor_mul",
                                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                        ValueLayout.ADDRESS)),
                        downcall(linker, lookup, "spike_tensor_matmul",
                                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                        ValueLayout.ADDRESS)),
                        downcall(linker, lookup, "spike_tensor_relu",
                                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
                        downcall(linker, lookup, "spike_tensor_read",
                                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)),
                        downcall(linker, lookup, "spike_tensor_destroy",
                                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)));
            } catch (CortexJException e) {
                throw e;
            } catch (Throwable e) {
                throw nativeError("cannot bind the libtorch shim: " + e, e);
            }
        }

        private static MethodHandle downcall(Linker linker, SymbolLookup lookup,
                                             String name, FunctionDescriptor descriptor) {
            try {
                return linker.downcallHandle(lookup.find(name).orElseThrow(), descriptor);
            } catch (Throwable e) {
                throw nativeError("shim is missing symbol '" + name + "'",
                        "Rebuild the shim from the pinned source.");
            }
        }

        MemorySegment create(Arena arena, float[] values, long rows, long columns)
                throws Throwable {
            MemorySegment buffer = arena.allocateFrom(ValueLayout.JAVA_FLOAT, values);
            MemorySegment handle = (MemorySegment) create.invokeExact(buffer, rows, columns);
            if (handle.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("native tensor creation failed");
            }
            return handle;
        }

        MemorySegment add(MemorySegment left, MemorySegment right) throws Throwable {
            return checked((MemorySegment) add.invokeExact(left, right), "add");
        }

        MemorySegment mul(MemorySegment left, MemorySegment right) throws Throwable {
            return checked((MemorySegment) mul.invokeExact(left, right), "mul");
        }

        MemorySegment matmul(MemorySegment left, MemorySegment right) throws Throwable {
            return checked((MemorySegment) matmul.invokeExact(left, right), "matmul");
        }

        MemorySegment relu(MemorySegment handle) throws Throwable {
            return checked((MemorySegment) relu.invokeExact(handle), "relu");
        }

        float[] read(MemorySegment handle, long elements) throws Throwable {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(ValueLayout.JAVA_FLOAT, elements);
                long count = (long) read.invokeExact(handle, out, elements);
                if (count != elements) {
                    throw new IllegalStateException("native read returned " + count
                            + ", expected " + elements);
                }
                return out.toArray(ValueLayout.JAVA_FLOAT);
            }
        }

        void destroy(MemorySegment handle) throws Throwable {
            destroy.invokeExact(handle);
        }

        private static MemorySegment checked(MemorySegment handle, String op) {
            if (handle.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("native " + op + " failed");
            }
            return handle;
        }
    }
}
