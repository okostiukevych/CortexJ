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

public class DjlProvider implements RuntimeProvider {

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
            engines.addAll(discoverEngines());
        } catch (Throwable discoveryFailure) {
            NativeRuntimeException failure = new NativeRuntimeException(
                    ErrorCode.NATIVE_LIBRARY_MISSING,
                    "DJL engine discovery failed: " + discoveryFailure,
                    "Verify the ai.djl:api dependency and classpath integrity.",
                    discoveryFailure);
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

    java.util.Collection<String> discoverEngines() {
        return Engine.getAllEngines();
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
        return new DjlModelRuntime(request.executor());
    }

    final class DjlModelRuntime implements ModelRuntime {

        private final java.util.concurrent.ExecutorService executor;

        DjlModelRuntime(java.util.concurrent.ExecutorService executor) {
            this.executor = executor;
        }
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
            boolean loadSucceeded = false;
            java.nio.file.Path modelDirectory = artifactPath.toAbsolutePath().getParent();
            java.nio.file.Path modelFileName = artifactPath.getFileName();
            try {
                model.load(modelDirectory,
                        modelFileName == null ? "model" : modelFileName.toString());
                modelLoaded = true;
                tokenizer = loadTokenizer(modelDirectory);
                DjlModel loaded = new DjlModel(model, tokenizer, executor);
                loadSucceeded = true;
                return loaded;
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
                if (!loadSucceeded) {
                    model.close();
                }
            }
        }

        private HfTokenizer.TokenEncoder loadTokenizer(Path modelDirectory) {
            Path tokenizerJson = modelDirectory == null ? null : modelDirectory.resolve("tokenizer.json");
            if (tokenizerJson != null && Files.isRegularFile(tokenizerJson)) {
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
        private final java.util.concurrent.ExecutorService executor;
        private final java.util.concurrent.atomic.AtomicBoolean closed =
                new java.util.concurrent.atomic.AtomicBoolean();
        // inference holds the READ lock so embeds run concurrently (GitHub #67);
        // close() holds the WRITE lock so it waits for in-flight inference
        // instead of tearing the model down under it (TOCTOU guarantee, audit H-1).
        // DJL Predictor thread-safety is engine-dependent, so a fresh
        // NDManager + Predictor is still created per call under the read lock.
        private final java.util.concurrent.locks.ReentrantReadWriteLock inferenceLock =
                new java.util.concurrent.locks.ReentrantReadWriteLock();

        DjlModel(Model model, HfTokenizer.TokenEncoder tokenizer,
                 java.util.concurrent.ExecutorService executor) {
            this.model = model;
            this.tokenizer = tokenizer;
            this.executor = executor;
            this.auxiliaryInputs = resolveAuxiliaryInputs(model);
        }

        /** Auxiliary INT64 model inputs fed with conventional defaults. */
        final class AuxiliaryInput {
            final String name;
            final boolean fillOnes;

            AuxiliaryInput(String name, boolean fillOnes) {
                this.name = name;
                this.fillOnes = fillOnes;
            }
        }

        private final List<AuxiliaryInput> auxiliaryInputs;

        /**
         * Reads the declared model inputs once at load. Mask-like inputs get
         * ones (every token attended — we never pad), other inputs get INT64
         * zeros (the {@code token_type_ids} convention). The declared shape
         * is informational only here: feeding a non-INT64 auxiliary fails
         * typed at inference instead of guessing values.
         */
        private List<AuxiliaryInput> resolveAuxiliaryInputs(Model model) {
            List<AuxiliaryInput> auxiliaries = new ArrayList<AuxiliaryInput>();
            ai.djl.util.PairList<String, ai.djl.ndarray.types.Shape> declared;
            try {
                declared = model.describeInput();
            } catch (Exception e) {
                LOG.warning("cannot introspect DJL inputs, auxiliary feeding disabled: " + e.getMessage());
                return auxiliaries;
            }
            if (declared == null) {
                return auxiliaries;
            }
            for (String name : declared.keys()) {
                if (name == null || "input_ids".equals(name)) {
                    continue;
                }
                boolean fillOnes = "attention_mask".equalsIgnoreCase(name)
                        || "mask".equalsIgnoreCase(name);
                auxiliaries.add(new AuxiliaryInput(name, fillOnes));
            }
            return auxiliaries;
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
            if (executor == null) {
                try {
                    return CompletableFuture.completedFuture(execute(request));
                } catch (Throwable failure) {
                    CompletableFuture<InferenceResponse> failed = new CompletableFuture<InferenceResponse>();
                    failed.completeExceptionally(failure);
                    return failed;
                }
            }
            try {
                return CompletableFuture.supplyAsync(new java.util.function.Supplier<InferenceResponse>() {
                    @Override
                    public InferenceResponse get() {
                        return execute(request);
                    }
                }, executor);
            } catch (java.util.concurrent.RejectedExecutionException saturated) {
                CompletableFuture<InferenceResponse> rejected = new CompletableFuture<InferenceResponse>();
                rejected.completeExceptionally(saturated);
                return rejected;
            }
        }

        @Override
        public EmbeddingResponse embed(EmbeddingRequest request) {
            if (closed.get()) {
                throw new InferenceException(ErrorCode.INFERENCE_FAILED,
                        "model is closed", "Reopen the model handle.");
            }
            io.cortexj.spi.support.EmbeddingLimits.checkEmbedLimits(request.texts());
            List<float[]> vectors = new ArrayList<float[]>();
            for (String text : request.texts()) {
                int[] ids = tokenizer.encode(text);
                vectors.add(embedTokenIds(ids));
            }
            return new EmbeddingResponse(vectors);
        }

        private float[] embedTokenIds(int[] ids) {
            int[] effectiveIds = (ids == null || ids.length == 0) ? new int[]{0} : ids;
            long[] longIds = new long[effectiveIds.length];
            for (int i = 0; i < effectiveIds.length; i++) {
                longIds[i] = effectiveIds[i];
            }
            java.util.concurrent.locks.Lock readLock = inferenceLock.readLock();
            readLock.lock();
            try {
                if (closed.get()) {
                    throw new InferenceException(ErrorCode.INFERENCE_FAILED,
                            "model is closed", "Reopen the model handle.");
                }
                try (NDManager manager = model.getNDManager().newSubManager()) {
                    NDArray input = manager.create(longIds, new ai.djl.ndarray.types.Shape(1, longIds.length));
                    input.setName("input_ids");
                    NDList inputList = new NDList(input);
                    for (AuxiliaryInput auxiliary : auxiliaryInputs) {
                        long[] fill = new long[longIds.length];
                        if (auxiliary.fillOnes) {
                            for (int i = 0; i < fill.length; i++) {
                                fill[i] = 1L;
                            }
                        }
                        NDArray tensor = manager.create(fill,
                                new ai.djl.ndarray.types.Shape(1, fill.length));
                        tensor.setName(auxiliary.name);
                        inputList.add(tensor);
                    }
                    try (ai.djl.inference.Predictor<NDList, NDList> predictor =
                                 model.newPredictor(new NoopTranslator())) {
                        NDList output = predictor.predict(inputList);
                        NDArray embedding = output.get(0);
                        float[] flat = embedding.toFloatArray();
                        long[] shape = embedding.getShape().getShape();
                        // hidden size is the LAST dimension: rank-2 [batch, dim]
                        // and rank-3 [batch, seq, hidden] transformer outputs
                        // share this layout (a middle dim is the sequence)
                        int dimension = shape.length == 0 ? 0 : (int) shape[shape.length - 1];
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
            } finally {
                readLock.unlock();
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
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            java.util.concurrent.locks.Lock writeLock = inferenceLock.writeLock();
            writeLock.lock();
            try {
                model.close();
            } finally {
                writeLock.unlock();
            }
        }
    }
}
