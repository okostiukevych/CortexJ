package io.cortexj.runtime.djl;

import ai.djl.Model;
import ai.djl.engine.Engine;
import ai.djl.engine.EngineException;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.translate.NoopTranslator;
import io.cortexj.ErrorCode;
import io.cortexj.error.InferenceException;
import io.cortexj.error.ModelLoadException;
import io.cortexj.error.NativeRuntimeException;
import io.cortexj.inference.EmbeddingCapability;
import io.cortexj.inference.EmbeddingRequest;
import io.cortexj.inference.EmbeddingResponse;
import io.cortexj.inference.InferenceRequest;
import io.cortexj.inference.InferenceResponse;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelCapabilities;
import io.cortexj.model.TaskCapability;
import io.cortexj.model.token.HfTokenizer;
import io.cortexj.runtime.DeviceType;
import io.cortexj.runtime.RuntimeDescriptor;
import io.cortexj.runtime.RuntimeId;
import io.cortexj.spi.runtime.ModelRuntime;
import io.cortexj.spi.runtime.RuntimeEvaluationRequest;
import io.cortexj.spi.runtime.RuntimeModel;
import io.cortexj.spi.runtime.RuntimeModelRequest;
import io.cortexj.spi.runtime.RuntimeOpenRequest;
import io.cortexj.spi.runtime.RuntimeProbeContext;
import io.cortexj.spi.runtime.RuntimeProbeResult;
import io.cortexj.spi.runtime.RuntimeProvider;
import io.cortexj.spi.runtime.RuntimeSupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.logging.Logger;

public final class DjlProvider implements RuntimeProvider {

    public static final String ENGINE_NAME = "OnnxRuntime";
    public static final String DJL_VERSION = "0.38.0";

    private static final Logger LOG = Logger.getLogger(DjlProvider.class.getName());

    private final RuntimeId id = RuntimeId.of("djl");
    private final RuntimeDescriptor descriptor = RuntimeDescriptor.builder(id)
            .version(DJL_VERSION)
            .format(ArtifactFormat.ONNX)
            .device(DeviceType.CPU)
            .task(TaskCapability.EMBEDDING)
            .minJavaFeatureVersion(8)
            .streamingSupported(false)
            .nativeRuntime(true)
            .threadSafety(io.cortexj.runtime.ThreadSafety.SESSION_SCOPED)
            .maturity(RuntimeDescriptor.Maturity.EXPERIMENTAL)
            .metadata("engine", "ai.djl " + DJL_VERSION + " (engine artifacts are optional)")
            .metadata("engineArtifact", "ai.djl.onnxruntime:onnxruntime-engine:" + DJL_VERSION)
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
        List<String> engines = new ArrayList<String>();
        try {
            engines.addAll(Engine.getAllEngines());
        } catch (Throwable discoveryFailure) {
            NativeRuntimeException failure = new NativeRuntimeException(
                    ErrorCode.NATIVE_LIBRARY_MISSING,
                    "DJL engine discovery failed: " + discoveryFailure,
                    "Verify the ai.djl:api dependency and classpath integrity.");
            failure.detail("runtime", id.value());
            return RuntimeProbeResult.unavailable(
                    "DJL engine discovery failed on this machine", failure);
        }
        for (String engine : engines) {
            if (engine.toLowerCase().startsWith(ENGINE_NAME.toLowerCase())) {
                return RuntimeProbeResult.available("DJL engines present: " + engines);
            }
        }
        NativeRuntimeException failure = new NativeRuntimeException(
                ErrorCode.NATIVE_LIBRARY_MISSING,
                "No ONNX-capable DJL engine is present on the classpath"
                        + (engines.isEmpty() ? " (no DJL engines found at all)" : " (found: " + engines + ")"),
                "Add the DJL ONNX engine to the assembly:\n"
                        + "  ai.djl.onnxruntime:onnxruntime-engine:" + DJL_VERSION + "\n"
                        + "  ai.djl.onnxruntime:onnxruntime-native-auto:" + DJL_VERSION
                        + " (or use the cortexj-onnx runtime instead)");
        failure.detail("runtime", id.value());
        failure.detail("engines", String.valueOf(engines));
        return RuntimeProbeResult.unavailable(
                "DJL is on the classpath but no ONNX engine artifacts are installed", failure);
    }

    @Override
    public RuntimeSupport evaluate(RuntimeEvaluationRequest request) {
        if (request.artifact() == null || request.artifact().format() != ArtifactFormat.ONNX) {
            return RuntimeSupport.unsupported(io.cortexj.planner.PlanReason.negative(
                    io.cortexj.planner.ReasonCode.FORMAT_UNSUPPORTED,
                    "DJL runtime only executes ONNX artifacts"));
        }
        return RuntimeSupport.full(DeviceType.CPU);
    }

    @Override
    public ModelRuntime open(RuntimeOpenRequest request) {
        RuntimeProbeResult probe = probe(new RuntimeProbeContext(
                request.environment(), request.hardware(), false, request.platformConfiguration()));
        if (!probe.isUsable()) {
            throw probe.error() == null
                    ? new NativeRuntimeException(ErrorCode.NATIVE_LIBRARY_MISSING, probe.message(),
                            "Add ai.djl.onnxruntime:onnxruntime-engine:" + DJL_VERSION
                                    + " and its native artifacts to the assembly.")
                    : probe.error();
        }
        return new DjlModelRuntime();
    }

    final class DjlModelRuntime implements ModelRuntime {

        @Override
        public RuntimeId runtimeId() {
            return id;
        }

        @Override
        public RuntimeModel load(RuntimeModelRequest request) {
            Path artifactPath = request.artifactPath();
            if (artifactPath == null || !Files.isRegularFile(artifactPath)) {
                ModelLoadException failure = new ModelLoadException(ErrorCode.LOAD_FAILED,
                        "ONNX artifact path is missing or does not exist: " + artifactPath,
                        "Pull the model first or check the file path.");
                failure.detail("path", String.valueOf(artifactPath));
                throw failure;
            }
            Model model;
            try {
                model = Model.newInstance("cortexj-djl", ENGINE_NAME);
            } catch (EngineException engineMissing) {
                NativeRuntimeException failure = new NativeRuntimeException(
                        ErrorCode.NATIVE_LIBRARY_MISSING,
                        "The DJL ONNX engine is not available: " + engineMissing.getMessage(),
                        "Add ai.djl.onnxruntime:onnxruntime-engine:" + DJL_VERSION
                                + " and onnxruntime-native-auto:" + DJL_VERSION
                                + " to the assembly, or use the cortexj-onnx runtime.");
                failure.detail("runtime", id.value());
                throw failure;
            }
            HfTokenizer.TokenEncoder tokenizer;
            boolean modelLoaded = false;
            try {
                model.load(artifactPath.toAbsolutePath().getParent(),
                        artifactPath.getFileName().toString());
                modelLoaded = true;
                tokenizer = loadTokenizer(artifactPath.toAbsolutePath().getParent());
            } catch (Exception loadFailure) {
                if (modelLoaded) {
                    throw new ModelLoadException(ErrorCode.LOAD_FAILED,
                            "Tokenizer for the DJL model could not be loaded: " + loadFailure,
                            "Ship tokenizer.json next to the model artifact.");
                }
                throw new ModelLoadException(ErrorCode.LOAD_FAILED,
                        "DJL could not load the ONNX model: " + loadFailure,
                        "Verify the artifact is a valid ONNX model for the OnnxRuntime engine.");
            } finally {
                if (!modelLoaded) {
                    model.close();
                }
            }
            return new DjlModel(model, tokenizer);
        }

        private HfTokenizer.TokenEncoder loadTokenizer(Path modelDirectory) {
            Path tokenizerJson = modelDirectory.resolve("tokenizer.json");
            if (Files.isRegularFile(tokenizerJson)) {
                try {
                    return HfTokenizer.load(tokenizerJson);
                } catch (Exception e) {
                    LOG.warning("tokenizer.json present but unreadable, falling back to whitespace tokenizer: "
                            + e.getMessage());
                }
            }
            return HfTokenizer.fallback();
        }

        @Override
        public void close() {
        }
    }

    final class DjlModel implements RuntimeModel, EmbeddingCapability {

        private final Model model;
        private final HfTokenizer.TokenEncoder tokenizer;
        private volatile boolean closed;

        DjlModel(Model model, HfTokenizer.TokenEncoder tokenizer) {
            this.model = model;
            this.tokenizer = tokenizer;
        }

        @Override
        public ModelCapabilities capabilities() {
            return ModelCapabilities.of(
                    Collections.singleton(TaskCapability.EMBEDDING), false, false, 4);
        }

        @Override
        public InferenceResponse execute(InferenceRequest request) {
            EmbeddingResponse embedding = embed(EmbeddingRequest.texts(request.inputs()));
            List<String> outputs = new ArrayList<String>();
            for (float[] vector : embedding.vectors()) {
                outputs.add(java.util.Arrays.toString(vector));
            }
            Map<String, Object> metadata = new LinkedHashMap<String, Object>();
            metadata.put("vectors", Integer.valueOf(embedding.vectors().size()));
            return new InferenceResponse(outputs, metadata);
        }

        @Override
        public CompletionStage<InferenceResponse> executeAsync(final InferenceRequest request) {
            return CompletableFuture.completedFuture(execute(request));
        }

        @Override
        public EmbeddingResponse embed(EmbeddingRequest request) {
            if (closed) {
                throw new InferenceException(ErrorCode.INFERENCE_FAILED,
                        "model is closed", "Reopen the model handle.");
            }
            List<float[]> vectors = new ArrayList<float[]>();
            for (String text : request.texts()) {
                int[] ids = tokenizer.encode(text);
                vectors.add(embedTokenIds(ids));
            }
            return new EmbeddingResponse(vectors);
        }

        private float[] embedTokenIds(int[] ids) {
            long[] longIds = new long[ids.length];
            for (int i = 0; i < ids.length; i++) {
                longIds[i] = ids[i];
            }
            try (NDManager manager = model.getNDManager().newSubManager()) {
                NDArray input = manager.create(longIds, new ai.djl.ndarray.types.Shape(1, longIds.length));
                input.setName("input_ids");
                NDList inputList = new NDList(input);
                try (ai.djl.inference.Predictor<NDList, NDList> predictor =
                             model.newPredictor(new NoopTranslator())) {
                    NDList output = predictor.predict(inputList);
                    NDArray embedding = output.get(0);
                    float[] flat = embedding.toFloatArray();
                    long[] shape = embedding.getShape().getShape();
                    int dimension = shape.length >= 2 ? (int) shape[1] : (int) shape[0];
                    return meanPool(flat, dimension);
                }
            } catch (InferenceException e) {
                throw e;
            } catch (Exception e) {
                InferenceException failure = new InferenceException(ErrorCode.INFERENCE_FAILED,
                        "DJL inference failed: " + e.getMessage(), e);
                failure.detail("runtime", id.value());
                throw failure;
            }
        }

        private float[] meanPool(float[] flat, int dimension) {
            if (flat.length == 0 || dimension <= 0 || flat.length % dimension != 0) {
                return flat;
            }
            int tokens = flat.length / dimension;
            float[] pooled = new float[dimension];
            for (int i = 0; i < flat.length; i++) {
                pooled[i % dimension] += flat[i];
            }
            for (int i = 0; i < dimension; i++) {
                pooled[i] /= tokens;
            }
            return pooled;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                model.close();
            }
        }
    }
}
