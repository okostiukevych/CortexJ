package io.cortexj.runtime.llamacpp;

import io.cortexj.ErrorCode;
import io.cortexj.error.NativeRuntimeException;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.TaskCapability;
import io.cortexj.runtime.DeviceType;
import io.cortexj.runtime.RuntimeDescriptor;
import io.cortexj.runtime.RuntimeId;
import io.cortexj.spi.runtime.ModelRuntime;
import io.cortexj.spi.runtime.RuntimeEvaluationRequest;
import io.cortexj.spi.runtime.RuntimeOpenRequest;
import io.cortexj.spi.runtime.RuntimeProbeContext;
import io.cortexj.spi.runtime.RuntimeProbeResult;
import io.cortexj.spi.runtime.RuntimeProvider;
import io.cortexj.spi.runtime.RuntimeSupport;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public final class LlamaCppProvider implements RuntimeProvider {

    public static final String LIB_ENV = "CORTEXJ_LLAMACPP_LIB";
    public static final String LIB_PROPERTY = "cortexj.llamacpp.lib";

    private final RuntimeId id = RuntimeId.of("llamacpp");
    private final RuntimeDescriptor descriptor = RuntimeDescriptor.builder(id)
            .version("spike-1")
            .format(ArtifactFormat.GGUF)
            .device(DeviceType.CPU)
            .device(DeviceType.GPU)
            .task(TaskCapability.TEXT_GENERATION)
            .task(TaskCapability.EMBEDDING)
            .minJavaFeatureVersion(11)
            .streamingSupported(true)
            .nativeRuntime(true)
            .threadSafety(io.cortexj.runtime.ThreadSafety.SESSION_SCOPED)
            .maturity(RuntimeDescriptor.Maturity.BETA)
            .metadata("binding", "JNI (planned)")
            .build();

    @Override
    public RuntimeId id() {
        return id;
    }

    @Override
    public RuntimeDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public RuntimeProbeResult probe(RuntimeProbeContext context) {
        List<String> candidates = candidateLibraries(context);
        for (String candidate : candidates) {
            File file = new File(candidate);
            if (file.isFile()) {
                return RuntimeProbeResult.available(
                        "llama.cpp native library found: " + candidate);
            }
        }
        NativeRuntimeException failure = new NativeRuntimeException(ErrorCode.NATIVE_LIBRARY_MISSING,
                nativeMissingMessage(candidates),
                "Alternatives:\n"
                        + "1. build llama.cpp and point " + LIB_ENV + " to libllama\n"
                        + "2. run 'llama-server' locally and use the openai-remote runtime "
                        + "(set CORTEXJ_REMOTE_BASE_URL=http://localhost:8080)\n"
                        + "3. use the ONNX runtime for embedding workloads");
        failure.detail("runtime", id.value());
        failure.detail("probedPaths", String.valueOf(candidates));
        return RuntimeProbeResult.unavailable(
                "llama.cpp native library (JNI binding) is not installed on this machine", failure);
    }

    private static String nativeMissingMessage(List<String> probed) {
        return "The llama.cpp runtime requires a native library build of llama.cpp with the CortexJ JNI bridge. "
                + "Probed locations: " + probed;
    }

    private static List<String> candidateLibraries(RuntimeProbeContext context) {
        List<String> candidates = new ArrayList<String>();
        String explicit = System.getProperty(LIB_PROPERTY);
        if (explicit != null && !explicit.isEmpty()) {
            candidates.add(explicit);
        }
        String fromEnv = System.getenv(LIB_ENV);
        if (fromEnv != null && !fromEnv.isEmpty()) {
            candidates.add(fromEnv);
        }
        String extension = context.environment() != null
                && context.environment().operatingSystem() != null
                && context.environment().operatingSystem().toLowerCase().contains("mac")
                ? "dylib" : "so";
        String libraryPath = System.getProperty("java.library.path", "");
        for (String dir : libraryPath.split(File.pathSeparator)) {
            if (!dir.isEmpty()) {
                candidates.add(new File(dir, "libllama." + extension).getAbsolutePath());
            }
        }
        String home = System.getProperty("user.home", ".");
        candidates.add(home + "/.cortexj/native/libllama." + extension);
        candidates.add("/usr/local/lib/libllama." + extension);
        candidates.add("/usr/lib/libllama." + extension);
        return candidates;
    }

    @Override
    public RuntimeSupport evaluate(RuntimeEvaluationRequest request) {
        if (request.artifact() == null || request.artifact().format() != ArtifactFormat.GGUF) {
            return RuntimeSupport.unsupported(io.cortexj.planner.PlanReason.negative(
                    io.cortexj.planner.ReasonCode.FORMAT_UNSUPPORTED,
                    "llama.cpp only executes GGUF artifacts"));
        }
        DeviceType device = request.hardware().hasAccelerator() ? DeviceType.GPU : DeviceType.CPU;
        return RuntimeSupport.full(device);
    }

    @Override
    public ModelRuntime open(RuntimeOpenRequest request) {
        RuntimeProbeResult probe = probe(new RuntimeProbeContext(
                request.environment(), request.hardware(), false, request.platformConfiguration()));
        if (!probe.isUsable()) {
            throw probe.error() == null
                    ? new NativeRuntimeException(ErrorCode.NATIVE_LIBRARY_MISSING, probe.message(),
                            "Build llama.cpp and point CORTEXJ_LLAMACPP_LIB at the native library.")
                    : probe.error();
        }
        return new LlamaCppModelRuntime();
    }

    final class LlamaCppModelRuntime implements ModelRuntime {

        @Override
        public RuntimeId runtimeId() {
            return id;
        }

        @Override
        public io.cortexj.spi.runtime.RuntimeModel load(io.cortexj.spi.runtime.RuntimeModelRequest request) {
            Path artifactPath = request.artifactPath();
            if (artifactPath == null || !java.nio.file.Files.isRegularFile(artifactPath)) {
                io.cortexj.error.ModelLoadException failure = new io.cortexj.error.ModelLoadException(
                        ErrorCode.LOAD_FAILED,
                        "GGUF artifact not found: " + artifactPath,
                        "Pull the model first or check the file path.");
                failure.detail("path", String.valueOf(artifactPath));
                throw failure;
            }
            throw new NativeRuntimeException(ErrorCode.NATIVE_LOAD_FAILED,
                    "The llama.cpp JNI execution bridge is not part of this build yet.",
                    "Until the JNI bridge ships, run llama.cpp's llama-server and use the "
                            + "openai-remote runtime against it.");
        }

        @Override
        public void close() {
        }
    }
}
