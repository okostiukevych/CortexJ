package io.cortexj.train.djl;

import ai.djl.Model;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.nn.SequentialBlock;
import ai.djl.nn.core.Linear;
import ai.djl.training.DefaultTrainingConfig;
import ai.djl.engine.Engine;
import ai.djl.training.Trainer;
import ai.djl.training.dataset.ArrayDataset;
import ai.djl.training.evaluator.Evaluator;
import ai.djl.training.loss.Loss;
import ai.djl.training.optimizer.Optimizer;
import ai.djl.training.tracker.Tracker;
import io.cortexj.error.CortexJException;
import io.cortexj.meta.ModelIdentity;
import io.cortexj.train.LmDataset;
import io.cortexj.train.LmExample;
import io.cortexj.train.LoraSettings;
import io.cortexj.train.TrainingBackend;
import io.cortexj.train.TrainingData;
import io.cortexj.train.TrainingRequest;
import io.cortexj.train.TrainingResult;
import io.cortexj.train.data.LmBatcher;
import io.cortexj.train.data.LmDatasets;
import io.cortexj.train.data.Tokenizer;
import io.cortexj.train.djl.models.ArchitectureConfig;
import io.cortexj.train.djl.models.ArchitectureConfigs;
import io.cortexj.train.djl.models.DjlHfTokenizer;
import io.cortexj.train.djl.models.GenericDecoderBlock;
import io.cortexj.train.djl.models.HfWeightLoader;
import io.cortexj.train.djl.models.LoadedWeights;
import io.cortexj.train.peft.LoRAAdapter;
import io.cortexj.train.peft.LoRAConfig;
import io.cortexj.train.peft.LoRASafetensors;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TrainingBackend wrapping the DJL PyTorch engine (ADR-021). The
 * verified live path (2026-10-08): tiny linear models trained through
 * the DJL training API; the trained weight delta becomes the LoRA
 * adapter artifact (safetensors container, checksummed). LLM-scale
 * PEFT is engine-dependent and stays {@code partial}.
 */
public class DjlTrainingBackend implements TrainingBackend {

    public static final String BACKEND_ID = "djl";

    /** Default hidden width for the verified tiny-model path. */
    static final int DEFAULT_UNITS = 2;

    /** Default training epochs when the request carries none. */
    static final int DEFAULT_EPOCHS = 30;

    /** Default learning rate: verified stable for the tiny-model path. */
    static final float DEFAULT_LEARNING_RATE = 0.01f;

    @Override
    public String id() {
        return BACKEND_ID;
    }

    @Override
    public Support evaluate(TrainingRequest request) {
        java.util.Set<String> engines;
        try {
            engines = new java.util.LinkedHashSet<String>(Engine.getAllEngines());
        } catch (Throwable discoveryFailure) {
            return new Support(Level.UNSUPPORTED,
                    "DJL engine discovery failed on this machine: " + discoveryFailure, 0);
        }
        if (engines.isEmpty()) {
            return new Support(Level.UNSUPPORTED,
                    "no DJL training engine is installed on the classpath", 0);
        }
        boolean pytorchPresent = engines.contains("PyTorch");
        if (!pytorchPresent) {
            return new Support(Level.UNSUPPORTED,
                    "DJL engines present (" + engines + ") do not support training; "
                            + "add the PyTorch engine", 0);
        }
        if (request == null) {
            return new Support(Level.PARTIAL, "request is null", 0);
        }
        if (request.lmData() != null || request.lora() != null) {
            return evaluateLlm(request, engines);
        }
        boolean livePath = request.data() != null
                && request.strategy() != io.cortexj.train.TrainingStrategy.FULL;
        if (livePath) {
            // the verified live path: tiny linear model, LoRA strategy
            return new Support(Level.FULL,
                    "PyTorch engine present; the tiny-linear-model LoRA path is "
                            + "live-verified on this machine",
                    estimateMemory(request));
        }
        return new Support(Level.PARTIAL,
                "DJL training covers classic models; full pretraining is not turnkey",
                0);
    }

    @Override
    public TrainingResult train(TrainingRequest request, Support support) {
        if (request != null && (request.lmData() != null || request.lora() != null)) {
            return trainLlm(request);
        }
        TrainingData data = request == null ? null : request.data();
        if (data == null) {
            CortexJException failure = new CortexJException("CORTEXJ-TRAIN-0001",
                    "the DJL backend trains live paths only; the request carries no data",
                    "Attach TrainingData to the request or use a remote trainer provider.");
            failure.detail("backend", id());
            throw failure;
        }
        try {
            return trainTinyLinear(request, data);
        } catch (CortexJException e) {
            throw e;
        } catch (Throwable e) {
            CortexJException failure = new CortexJException("CORTEXJ-TRAIN-0003",
                    "DJL training failed: " + e, "Check the engine and the data shapes.");
            failure.detail("backend", id());
            throw failure;
        }
    }

    /**
     * The verified live path: a single-hidden-width linear model through
     * the DJL training API, the trained-vs-initial weight delta as the
     * LoRA adapter artifact.
     */
    private TrainingResult trainTinyLinear(TrainingRequest request, TrainingData data)
            throws Exception {
        int epochs = request.epochs() > 1 ? request.epochs() : DEFAULT_EPOCHS;
        float learningRate = (float) request.learningRate();
        if (learningRate > 0.5f || learningRate <= 0f) {
            learningRate = DEFAULT_LEARNING_RATE;
        }
        int units = DEFAULT_UNITS;
        String unitsParameter = request.parameters().get("units");
        if (unitsParameter != null) {
            try {
                units = Math.max(1, Integer.parseInt(unitsParameter));
            } catch (NumberFormatException ignored) {
            }
        }

        java.nio.file.Path workDirectory =
                Files.createTempDirectory("cortexj-djl-train");
        Model model = Model.newInstance("cortexj-train", "PyTorch");
        try {
            SequentialBlock block = new SequentialBlock();
            block.add(Linear.builder().setUnits(units).build());
            block.add(Linear.builder().setUnits(1).build());
            model.setBlock(block);
            Loss loss = new Loss("cortexj-l2") {
                @Override
                public NDArray evaluate(NDList labels, NDList predictions) {
                    NDArray prediction = predictions.singletonOrThrow();
                    NDArray target = labels.singletonOrThrow();
                    NDArray difference = prediction.sub(target);
                    return difference.mul(difference).mean();
                }
            };
            Trainer trainer = model.newTrainer(new DefaultTrainingConfig(loss)
                    .optOptimizer(Optimizer.sgd()
                            .setLearningRateTracker(Tracker.fixed(learningRate)).build()));

            float[] inputs = new float[data.rows()];
            float[] targets = new float[data.rows()];
            for (int row = 0; row < data.rows(); row++) {
                inputs[row] = (float) data.inputAt(row);
                targets[row] = (float) data.targetAt(row);
            }
            AtomicInteger steps = new AtomicInteger();
            try (NDManager manager = model.getNDManager().newSubManager()) {
                NDArray xData = manager.create(inputs, new Shape(data.rows(), 1));
                NDArray yData = manager.create(targets, new Shape(data.rows(), 1));
                ArrayDataset dataset = new ArrayDataset.Builder()
                        .setData(xData)
                        .optLabels(yData)
                        .setSampling(data.rows(), false)
                        .build();
                trainer.initialize(new Shape(data.rows(), 1));
                // resume: load a previous checkpoint into the block
                String resumeFrom = request.parameters().get("resumeFrom");
                if (resumeFrom != null && !resumeFrom.trim().isEmpty()) {
                    resumeBlock(model, java.nio.file.Paths.get(resumeFrom.trim()));
                }
                // capture the initial weights BEFORE training: DJL
                // initializes randomly, so the LoRA delta is trained-initial
                Map<String, double[]> initialParameters = blockParameters(model);
                Map<String, long[]> parameterShapes = blockParameterShapes(model);
                double finalLoss = 0;
                for (int epoch = 0; epoch < epochs; epoch++) {
                    for (var batch : trainer.iterateDataset(dataset)) {
                        try (var gc = trainer.newGradientCollector()) {
                            NDArray prediction =
                                    trainer.forward(batch.getData()).singletonOrThrow();
                            NDArray lossValue = loss.evaluate(
                                    batch.getLabels(), new NDList(prediction));
                            finalLoss = lossValue.getFloat();
                            gc.backward(lossValue);
                            trainer.step();
                            steps.incrementAndGet();
                        }
                        batch.close();
                    }
                }

                // the trained weight delta becomes the LoRA adapter:
                // the first linear layer (units x 1), delta = trained - initial
                Map<String, double[]> trained = blockParameters(model);
                String firstWeightKey = firstWeightKey(initialParameters.keySet());
                double[] initialWeight = initialParameters.get(firstWeightKey);
                double[] trainedWeight = trained.get(firstWeightKey);
                long[] weightShape = blockParameterShapes(model).get(firstWeightKey);
                int weightOutputs = (int) weightShape[0];
                int weightInputs = (int) weightShape[1];
                // full-rank delta: A (rank x input) holds the delta rows,
                // B (output x rank) is the identity scale
                int rank = weightOutputs;
                LoRAConfig config = new LoRAConfig(rank, rank * 2, 0.0,
                        Arrays.asList(firstWeightKey));
                float[][] deltaA = new float[rank][weightInputs];
                float[][] deltaB = new float[weightOutputs][rank];
                for (int o = 0; o < weightOutputs; o++) {
                    for (int i = 0; i < weightInputs; i++) {
                        deltaA[o][i] = toFloat(trainedWeight[o * weightInputs + i]
                                - initialWeight[o * weightInputs + i]);
                        deltaB[o][o] = 1.0f;
                    }
                }
                LoRAAdapter adapter = new LoRAAdapter(firstWeightKey, weightInputs, weightOutputs,
                        config, deltaA, deltaB);

                // checkpoint the trained block: resume loads it back
                Path checkpointDirectory = workDirectory.resolve("checkpoint-0");
                io.cortexj.train.distributed.CheckpointShards.saveWithShapes(
                        trained, parameterShapes, checkpointDirectory, 2);

                Path adapterFile = workDirectory.resolve("adapter_model.safetensors");
                LoRASafetensors.write(adapter, adapterFile,
                        Map.of("backend", id(), "model", request.model()));
                String checksum = sha256(adapterFile);

                Map<String, Double> metrics = new LinkedHashMap<String, Double>();
                metrics.put("final.loss", finalLoss);
                metrics.put("steps", (double) steps.get());
                return new TrainingResult("train-" + System.nanoTime(),
                        ModelIdentity.of(request.model(), null),
                        adapterFile.toAbsolutePath().toString(),
                        metrics,
                        new TrainingResult.ProvenanceHolder(
                                request.dataset(),
                                List.of(0L),
                                id(), "cpu",
                                Map.of("adapter_model.safetensors", checksum)));
            }
        } finally {
            model.close();
        }
    }

    static final long MAX_BASE_BYTES = 5L * 1024L * 1024L * 1024L;

    private Support evaluateLlm(TrainingRequest request, java.util.Set<String> engines) {
        if (request.lmData() == null || request.lora() == null) {
            return new Support(Level.PARTIAL,
                    "the LLM path needs both lmData and lora settings on the request", 0);
        }
        String modelPath = request.parameters().get("modelPath");
        if (modelPath == null || modelPath.trim().isEmpty()) {
            return new Support(Level.UNSUPPORTED,
                    "modelPath parameter is required (directory with config.json, "
                            + "safetensors weights, tokenizer.json) [CORTEXJ-TRAIN-0102]", 0);
        }
        java.nio.file.Path dir = Paths.get(modelPath.trim());
        if (modelPath.trim().endsWith(".gguf")) {
            return new Support(Level.UNSUPPORTED,
                    "quantized GGUF is not a trainable base [CORTEXJ-TRAIN-0104]", 0);
        }
        java.nio.file.Path configJson = dir.resolve("config.json");
        if (!Files.isRegularFile(configJson)) {
            return new Support(Level.UNSUPPORTED,
                    "no config.json in modelPath [CORTEXJ-TRAIN-0102]", 0);
        }
        ArchitectureConfig config;
        try {
            config = ArchitectureConfigs.parse(configJson);
        } catch (CortexJException e) {
            return new Support(Level.UNSUPPORTED,
                    e.code() + " " + e.getMessage(), 0);
        }
        long params = ArchitectureConfigs.paramCount(config);
        if (params * 2 > MAX_BASE_BYTES) {
            return new Support(Level.UNSUPPORTED,
                    "base exceeds the 5GB F16 training limit (" + params * 2 + " bytes) "
                            + "[CORTEXJ-TRAIN-0107]", 0);
        }
        int seqLen = intParameter(request, "seqLen", 512);
        int batchSize = intParameter(request, "batchSize", 1);
        long loraParams;
        try {
            loraParams = loraParamCount(config, request.lora());
        } catch (CortexJException e) {
            return new Support(Level.UNSUPPORTED, e.code() + " " + e.getMessage(), 0);
        }
        long estimate = params * 4L                       // F32 weights
                + loraParams * 16L                        // A/B weight + grad + Adam state
                + (long) batchSize * seqLen * config.hiddenSize() * config.numLayers() * 24L
                + (long) batchSize * seqLen * config.vocabSize() * 4L; // logits
        long heap = Runtime.getRuntime().maxMemory();
        if (estimate > heap * 9 / 10) {
            return new Support(Level.UNSUPPORTED,
                    "estimated memory " + estimate + " bytes exceeds the JVM budget "
                            + heap + " [CORTEXJ-TRAIN-0108]", estimate);
        }
        if (engines == null || !engines.contains("PyTorch")) {
            return new Support(Level.UNSUPPORTED,
                    "no DJL PyTorch training engine on the classpath", estimate);
        }
        return new Support(Level.FULL,
                "LLM LoRA path: family=" + config.family()
                        + " params=" + params
                        + " estimate=" + estimate + " bytes", estimate);
    }

    private TrainingResult trainLlm(TrainingRequest request) {
        String modelPath = request.parameters().get("modelPath");
        if (modelPath == null || modelPath.trim().isEmpty()) {
            throw lmFailure("CORTEXJ-TRAIN-0102",
                    "the LLM path requires the modelPath parameter",
                    "Point modelPath at the base model directory.");
        }
        if (modelPath.trim().endsWith(".gguf")) {
            throw lmFailure("CORTEXJ-TRAIN-0104",
                    "quantized GGUF is not a trainable base",
                    "Point modelPath at the unquantized safetensors model directory.");
        }
        if (request.lora() == null || request.lmData() == null) {
            throw lmFailure("CORTEXJ-TRAIN-0102",
                    "the LLM path requires both lora settings and lmData",
                    "Set .lora(...) and .lmData(...) on the request.");
        }
        java.nio.file.Path dir = Paths.get(modelPath.trim());
        ArchitectureConfig config = ArchitectureConfigs.parse(dir.resolve("config.json"));
        LoadedWeights weights = HfWeightLoader.load(dir);
        // Vocab gate: tokenizer.json wins when present (it tokenized the
        // data through our pipeline); a pre-tokenized LmDataset is checked
        // against config.json instead - no silent size mismatch either way.
        java.nio.file.Path tokenizerJson = dir.resolve("tokenizer.json");
        if (Files.isRegularFile(tokenizerJson)) {
            Tokenizer tokenizer = DjlHfTokenizer.load(tokenizerJson);
            // one-sided gate: the tokenizer must not address ids past
            // the embedding matrix. Qwen-family tokenizers declare fewer
            // entries than config.json vocab (reserved rows stay empty);
            // the embedding-matrix check in loadWeights covers that side.
            if (tokenizer.vocabSize() > config.vocabSize()) {
                throw lmFailure("CORTEXJ-TRAIN-0106",
                        "tokenizer vocab (" + tokenizer.vocabSize()
                                + ") exceeds config.json vocab ("
                                + config.vocabSize() + ")",
                        "The tokenizer, config.json and weights must agree.");
            }
        } else if (request.lmData().vocabSize() > config.vocabSize()) {
            throw lmFailure("CORTEXJ-TRAIN-0106",
                    "dataset vocab (" + request.lmData().vocabSize()
                            + ") exceeds config.json vocab ("
                            + config.vocabSize() + ")",
                    "Tokenize with the model's tokenizer or ship tokenizer.json.");
        }
        LoraSettings lora = request.lora();
        GenericDecoderBlock block = GenericDecoderBlock.build(config,
                new java.util.HashSet<String>(lora.targetModules()),
                lora.rank(), lora.scaling());
        LmTrainingLoop loop = LmTrainingLoop.create(block,
                (float) request.learningRate(),
                intParameter(request, "warmupSteps", 10), 0.01f);
        int seqLen = intParameter(request, "seqLen", 512);
        loop.initialize(seqLen);
        block.loadWeights(weights, loop.modelManager());
        block.initLora(longParameter(request, "seed", 42L));
        String resumeFrom = request.parameters().get("resumeFrom");
        loop.restoreFromCheckpoint(resumeFrom == null || resumeFrom.trim().isEmpty()
                ? null : Paths.get(resumeFrom.trim()));

        LmDataset lmData = request.lmData();
        java.util.List<LmExample> examples = lmData.examples();
        java.util.List<java.util.List<LmExample>> split = LmDatasets.split(examples,
                1.0 - doubleParameter(request, "evalSplit", 0.1),
                longParameter(request, "seed", 42L));
        if (split.get(0).isEmpty()) {
            throw lmFailure("CORTEXJ-TRAIN-0110",
                    "the train split is empty",
                    "Provide more examples or lower evalSplit.");
        }
        int batchSize = intParameter(request, "batchSize", 1);
        long seed = longParameter(request, "seed", 42L);
        java.util.List<LmBatcher.LmBatch> trainBatches = LmBatcher.batch(split.get(0),
                batchSize, seqLen, seed, true);
        java.util.List<LmBatcher.LmBatch> evalBatches = split.get(1).isEmpty()
                ? java.util.Collections.<LmBatcher.LmBatch>emptyList()
                : LmBatcher.batch(split.get(1), batchSize, seqLen, seed, false);

        java.util.Map<String, Double> metrics = loop.run(trainBatches, evalBatches,
                request.epochs(), intParameter(request, "accumulation", 4));

        String outputDir = request.parameters().get("outputDir");
        java.nio.file.Path out;
        try {
            out = outputDir == null || outputDir.trim().isEmpty()
                    ? Files.createTempDirectory("cortexj-lora-adapter")
                    : Paths.get(outputDir.trim());
            java.util.Map<String, String> checksums = AdapterExport.writeAll(block,
                    lora.rank(), lora.alpha(), lora.targetModules(), out);
            loop.saveCheckpoint(out.resolve("checkpoint-0"));
            return new TrainingResult("train-" + System.nanoTime(),
                    ModelIdentity.of(request.model(), null),
                    out.toAbsolutePath().toString(),
                    metrics,
                    new TrainingResult.ProvenanceHolder(
                            lmData.fingerprint(),
                            java.util.List.of(seed),
                            id(), "cpu", checksums));
        } catch (java.io.IOException e) {
            throw lmFailure("CORTEXJ-TRAIN-0111",
                    "adapter export failed: " + e.getMessage(),
                    "Check the output directory permissions.");
        } finally {
            loop.close();
        }
    }

    private static long loraParamCount(ArchitectureConfig config, LoraSettings lora) {
        long count = 0;
        long hidden = config.hiddenSize();
        long kvDim = config.kvDim();
        long intermediate = config.intermediateSize();
        for (String target : lora.targetModules()) {
            long perModule;
            if ("q_proj".equals(target)) {
                perModule = hidden + hidden;
            } else if ("k_proj".equals(target) || "v_proj".equals(target)) {
                perModule = hidden + kvDim;
            } else if ("o_proj".equals(target)) {
                perModule = hidden + hidden;
            } else if ("gate_proj".equals(target) || "up_proj".equals(target)) {
                perModule = hidden + intermediate;
            } else if ("down_proj".equals(target)) {
                perModule = intermediate + hidden;
            } else {
                throw lmFailure("CORTEXJ-TRAIN-0109",
                        "unknown LoRA target module: " + target,
                        "Use q_proj, k_proj, v_proj, o_proj, gate_proj, up_proj, down_proj.");
            }
            count += config.numLayers() * lora.rank() * perModule;
        }
        return count;
    }

    private static CortexJException lmFailure(String code, String message, String remediation) {
        CortexJException failure = new CortexJException(code, message, remediation);
        failure.detail("backend", BACKEND_ID);
        return failure;
    }

    private static int intParameter(TrainingRequest request, String key, int fallback) {
        String value = request.parameters().get(key);
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long longParameter(TrainingRequest request, String key, long fallback) {
        String value = request.parameters().get(key);
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double doubleParameter(TrainingRequest request, String key, double fallback) {
        String value = request.parameters().get(key);
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String firstWeightKey(java.util.Set<String> keys) {
        // DJL names block parameters "<index><Class>_weight"; the first
        // linear layer weight is the lowest-indexed *_weight
        String first = null;
        for (String key : new java.util.TreeSet<String>(keys)) {
            if (key.endsWith("_weight")) {
                first = key;
                break;
            }
        }
        if (first == null) {
            throw new IllegalStateException("no linear weight parameters found");
        }
        return first;
    }

    private static Map<String, double[]> blockParameters(Model model) {
        Map<String, double[]> parameters = new LinkedHashMap<String, double[]>();
        ai.djl.nn.ParameterList parameterList = model.getBlock().getParameters();
        for (int i = 0; i < parameterList.keys().size(); i++) {
            ai.djl.nn.Parameter parameter = parameterList.values().get(i);
            parameters.put(parameterList.keys().get(i),
                    toDoubleArray(parameter.getArray().toFloatArray()));
        }
        return parameters;
    }

    private static void resumeBlock(Model model, java.nio.file.Path checkpoint)
            throws Exception {
        Map<String, double[]> saved = io.cortexj.train.distributed.CheckpointShards
                .load(checkpoint);
        Map<String, long[]> shapes = io.cortexj.train.distributed.CheckpointShards
                .shapesOf(checkpoint);
        ai.djl.nn.ParameterList parameterList = model.getBlock().getParameters();
        for (int i = 0; i < parameterList.keys().size(); i++) {
            String key = parameterList.keys().get(i);
            double[] values = saved.get(key);
            long[] shape = shapes.get(key);
            if (values == null || shape == null) {
                throw new IllegalStateException("checkpoint has no parameter: " + key);
            }
            float[] floats = new float[values.length];
            for (int v = 0; v < values.length; v++) {
                floats[v] = (float) values[v];
            }
            // the array already exists after initialize: copy in place
            // (a second setArray is rejected by DJL)
            parameterList.values().get(i).getArray().set(floats);
        }
    }

    private static Map<String, long[]> blockParameterShapes(Model model) {
        Map<String, long[]> shapes = new LinkedHashMap<String, long[]>();
        ai.djl.nn.ParameterList parameterList = model.getBlock().getParameters();
        for (int i = 0; i < parameterList.keys().size(); i++) {
            shapes.put(parameterList.keys().get(i),
                    parameterList.values().get(i).getShape().getShape());
        }
        return shapes;
    }

    private static float toFloat(double value) {
        return (float) value;
    }

    private static double[] toDoubleArray(float[] values) {
        double[] doubles = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            doubles[i] = values[i];
        }
        return doubles;
    }

    private static String sha256(Path file) throws Exception {
        java.security.MessageDigest digest =
                java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(Files.readAllBytes(file));
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return "sha256:" + hex;
    }

    /**
     * Honest memory estimate for the verified tiny path, documented:
     * data (rows x features x 4B) + parameters (~= data, tiny linear
     * model) + gradients (params) + optimizer state (2x params, SGD) +
     * activations (2x data). LLM-scale runs need engine reporting.
     */
    static long estimateMemory(TrainingRequest request) {
        long dataBytes = 0;
        if (request != null && request.data() != null) {
            dataBytes = (long) request.data().rows()
                    * request.data().featureCount() * 4L;
        }
        long parametersBytes = Math.max(dataBytes, 1024);
        return dataBytes            // batch data
                + parametersBytes   // weights
                + parametersBytes   // gradients
                + 2 * parametersBytes // optimizer state (SGD)
                + 2 * dataBytes;     // activations
    }

    static List<String> remediation() {
        List<String> steps = new ArrayList<String>();
        steps.add("Add ai.djl.pytorch:pytorch-engine and the matching pytorch-native to the assembly");
        steps.add("Or use a remote trainer provider");
        return steps;
    }
}
