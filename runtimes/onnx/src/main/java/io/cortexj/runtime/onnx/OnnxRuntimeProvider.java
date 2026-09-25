package io.cortexj.runtime.onnx;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import io.cortexj.ErrorCode;
import io.cortexj.error.InferenceException;
import io.cortexj.error.ModelLoadException;
import io.cortexj.inference.EmbeddingRequest;
import io.cortexj.inference.EmbeddingResponse;
import io.cortexj.inference.InferenceRequest;
import io.cortexj.inference.InferenceResponse;
import io.cortexj.inference.TokenUsage;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelCapabilities;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.model.TaskCapability;
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

import java.nio.LongBuffer;
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

public final class OnnxRuntimeProvider implements RuntimeProvider {

    private static final Logger LOG = Logger.getLogger(OnnxRuntimeProvider.class.getName());

    private final RuntimeId id = RuntimeId.of("onnx");
    private final RuntimeDescriptor descriptor = RuntimeDescriptor.builder(id)
            .version("1.19")
            .format(ArtifactFormat.ONNX)
            .device(DeviceType.CPU)
            .task(TaskCapability.EMBEDDING)
            .task(TaskCapability.CLASSIFICATION)
            .minJavaFeatureVersion(8)
            .streamingSupported(false)
            .nativeRuntime(true)
            .threadSafety(io.cortexj.runtime.ThreadSafety.STATELESS)
            .maturity(RuntimeDescriptor.Maturity.STABLE)
            .metadata("engine", "Microsoft ONNX Runtime")
            .metadata("executionProvider", "CPU (GPU EP requires a CUDA-enabled onnxruntime build)")
            .build();

    private volatile OrtEnvironment environment;

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
        try {
            environment();
            return RuntimeProbeResult.available("ONNX Runtime native library loaded");
        } catch (Throwable e) {
            io.cortexj.error.RuntimeUnavailableException failure =
                    new io.cortexj.error.RuntimeUnavailableException(ErrorCode.RUNTIME_NOT_INSTALLED,
                            "ONNX Runtime native library is not available: " + e.getMessage(),
                            "Add the 'onnxruntime' dependency (bundled natives) or use another runtime provider.");
            failure.detail("runtime", id.value());
            return RuntimeProbeResult.unavailable("ONNX Runtime native library could not be loaded", failure);
        }
    }

    OrtEnvironment environment() {
        OrtEnvironment current = environment;
        if (current == null) {
            synchronized (this) {
                current = environment;
                if (current == null) {
                    current = OrtEnvironment.getEnvironment();
                    environment = current;
                }
            }
        }
        return current;
    }

    @Override
    public RuntimeSupport evaluate(RuntimeEvaluationRequest request) {
        if (request.artifact() == null || request.artifact().format() != ArtifactFormat.ONNX) {
            return RuntimeSupport.unsupported(io.cortexj.planner.PlanReason.negative(
                    io.cortexj.planner.ReasonCode.FORMAT_UNSUPPORTED,
                    "onnx runtime only executes ONNX artifacts"));
        }
        return RuntimeSupport.full(DeviceType.CPU);
    }

    @Override
    public ModelRuntime open(RuntimeOpenRequest request) {
        environment();
        return new OnnxModelRuntime(environment());
    }

    final class OnnxModelRuntime implements ModelRuntime {

        private final OrtEnvironment env;

        OnnxModelRuntime(OrtEnvironment env) {
            this.env = env;
        }

        @Override
        public RuntimeId runtimeId() {
            return id;
        }

        @Override
        public RuntimeModel load(RuntimeModelRequest request) {
            Path modelPath = request.artifactPath();
            if (modelPath == null || !Files.isRegularFile(modelPath)) {
                ModelLoadException failure = new ModelLoadException(ErrorCode.LOAD_FAILED,
                        "ONNX artifact path is missing or does not exist: " + modelPath,
                        "Verify the artifact was downloaded or referenced correctly.");
                failure.detail("path", String.valueOf(modelPath));
                throw failure;
            }
            try {
                byte[] modelBytes = Files.readAllBytes(modelPath);
                OrtSession session = env.createSession(modelBytes,
                        new OrtSession.SessionOptions());
                io.cortexj.model.token.HfTokenizer.TokenEncoder tokenizer = loadTokenizer(modelPath);
                return new OnnxModel(session, tokenizer);
            } catch (io.cortexj.error.CortexJException e) {
                throw e;
            } catch (Exception e) {
                ModelLoadException failure = new ModelLoadException(ErrorCode.LOAD_FAILED,
                        "Cannot create ONNX session from " + modelPath + ": " + e.getMessage(), e);
                failure.detail("path", modelPath.toString());
                throw failure;
            }
        }

        private io.cortexj.model.token.HfTokenizer.TokenEncoder loadTokenizer(Path modelPath) {
            Path tokenizerJson = modelPath.resolveSibling("tokenizer.json");
            if (!Files.isRegularFile(tokenizerJson)) {
                tokenizerJson = modelPath.getParent() == null
                        ? null
                        : modelPath.getParent().resolve("tokenizer.json");
            }
            if (tokenizerJson != null && Files.isRegularFile(tokenizerJson)) {
                try {
                    return io.cortexj.model.token.HfTokenizer.load(tokenizerJson);
                } catch (Exception e) {
                    LOG.warning("tokenizer.json present but unreadable, falling back to whitespace tokenizer: "
                            + e.getMessage());
                }
            }
            return io.cortexj.model.token.HfTokenizer.fallback();
        }

        @Override
        public void close() {
        }
    }

    final class OnnxModel implements RuntimeModel, io.cortexj.inference.EmbeddingCapability {

        private final OrtSession session;
        private final io.cortexj.model.token.HfTokenizer.TokenEncoder tokenizer;
        private volatile boolean closed;

        OnnxModel(OrtSession session, io.cortexj.model.token.HfTokenizer.TokenEncoder tokenizer) {
            this.session = session;
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
                throw new InferenceException(ErrorCode.INFERENCE_FAILED, "model is closed", "Reopen the model handle.");
            }
            List<float[]> vectors = new ArrayList<float[]>();
            long totalTokens = 0;
            for (String text : request.texts()) {
                int[] ids = tokenizer.encode(text);
                totalTokens += ids.length;
                float[] vector = runSession(ids);
                vectors.add(vector);
            }
            return new EmbeddingResponse(vectors);
        }

        private float[] runSession(int[] ids) {
            long[] longIds = new long[ids.length];
            for (int i = 0; i < ids.length; i++) {
                longIds[i] = ids[i];
            }
            try (OnnxTensor input = OnnxTensor.createTensor(environment(),
                    LongBuffer.wrap(longIds), new long[]{1, longIds.length})) {
                String inputName = session.getInputNames().iterator().next();
                Map<String, OnnxTensor> inputs = new LinkedHashMap<String, OnnxTensor>();
                inputs.put(inputName, input);
                try (OrtSession.Result result = session.run(inputs)) {
                    float[][] raw = (float[][]) result.get(0).getValue();
                    return meanPool(raw);
                }
            } catch (Exception e) {
                InferenceException failure = new InferenceException(ErrorCode.INFERENCE_FAILED,
                        "ONNX inference failed: " + e.getMessage(), e);
                failure.detail("runtime", id.value());
                throw failure;
            }
        }

        private float[] meanPool(float[][] tokenVectors) {
            if (tokenVectors.length == 0) {
                return new float[0];
            }
            int dim = tokenVectors[0].length;
            float[] pooled = new float[dim];
            for (float[] vector : tokenVectors) {
                for (int i = 0; i < dim; i++) {
                    pooled[i] += vector[i];
                }
            }
            for (int i = 0; i < dim; i++) {
                pooled[i] /= tokenVectors.length;
            }
            return pooled;
        }

        @Override
        public void close() {
            closed = true;
            try {
                session.close();
            } catch (Exception ignored) {
            }
        }
    }
}
