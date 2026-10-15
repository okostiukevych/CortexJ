package io.cortexj.train.djl;

import ai.djl.Model;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.training.DefaultTrainingConfig;
import ai.djl.training.GradientCollector;
import ai.djl.training.Trainer;
import ai.djl.training.optimizer.Optimizer;
import ai.djl.training.tracker.Tracker;
import ai.djl.training.tracker.WarmUpTracker;
import io.cortexj.error.CortexJException;
import io.cortexj.train.data.LmBatcher;
import io.cortexj.train.djl.models.GenericDecoderBlock;
import io.cortexj.train.djl.models.LoraLinear;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The LoRA training loop over the generic decoder: AdamW with
 * warmup, gradient accumulation, masked CE, epoch metrics, and
 * checkpoint save/restore of the LoRA A/B state only (the base is
 * immutable and re-read per run).
 */
final class LmTrainingLoop {

    private final GenericDecoderBlock block;
    private final Model model;
    private final Trainer trainer;
    private final MaskedCausalCrossEntropy loss = new MaskedCausalCrossEntropy();

    private LmTrainingLoop(GenericDecoderBlock block, Model model, Trainer trainer) {
        this.block = block;
        this.model = model;
        this.trainer = trainer;
    }

    static LmTrainingLoop create(GenericDecoderBlock block, float learningRate,
                                 int warmupSteps, float weightDecay) {
        Model model = Model.newInstance("cortexj-lm", "PyTorch");
        model.setBlock(block);
        Tracker tracker = Tracker.warmUp()
                .setMainTracker(Tracker.fixed(learningRate))
                .optWarmUpSteps(Math.max(1, warmupSteps))
                .optWarmUpBeginValue(0f)
                .optWarmUpMode(WarmUpTracker.Mode.LINEAR)
                .build();
        Optimizer optimizer = Optimizer.adamW()
                .optLearningRateTracker(tracker)
                .optWeightDecays(weightDecay)
                .build();
        Trainer trainer = model.newTrainer(
                new DefaultTrainingConfig(new MaskedCausalCrossEntropy())
                        .optOptimizer(optimizer));
        return new LmTrainingLoop(block, model, trainer);
    }

    NDManager modelManager() {
        return model.getNDManager();
    }

    void initialize(int maxSeqLen) {
        trainer.initialize(new Shape(1, maxSeqLen));
    }

    /** Eval-mode forward over one (1, T) id batch: logits (1, T, vocab). */
    NDList evalForward(int[] ids) {
        NDArray input = trainer.getManager().create(ids, new Shape(1, ids.length));
        return trainer.evaluate(new NDList(input));
    }

    Map<String, Double> run(List<LmBatcher.LmBatch> trainBatches,
                            List<LmBatcher.LmBatch> evalBatches,
                            int epochs, int accumulation) {
        double firstLoss = Double.NaN;
        double lastLoss = Double.NaN;
        long steps = 0;
        int micro = 0;
        for (int epoch = 0; epoch < epochs; epoch++) {
            for (LmBatcher.LmBatch batch : trainBatches) {
                NDArray lossValue = stepLoss(batch, accumulation);
                lastLoss = lossValue.getFloat();
                if (Double.isNaN(firstLoss)) {
                    firstLoss = lastLoss;
                }
                micro++;
                if (micro % accumulation == 0) {
                    trainer.step();
                    steps++;
                }
            }
        }
        if (micro % accumulation != 0) {
            trainer.step();
            steps++;
        }
        Map<String, Double> metrics = new LinkedHashMap<String, Double>();
        metrics.put("train.loss.first", firstLoss);
        metrics.put("train.loss.last", lastLoss);
        metrics.put("steps", (double) steps);
        if (!evalBatches.isEmpty()) {
            metrics.put("eval.loss", evalLoss(evalBatches));
        }
        return metrics;
    }

    void saveCheckpoint(java.nio.file.Path directory) {
        Map<String, double[]> state = new LinkedHashMap<String, double[]>();
        Map<String, long[]> shapes = new LinkedHashMap<String, long[]>();
        for (Map.Entry<String, LoraLinear> entry : block.loraChildren().entrySet()) {
            String name = entry.getKey();
            float[][] a = entry.getValue().matrixA();
            float[][] b = entry.getValue().matrixB();
            state.put(name + ".A", toDoubles(a));
            state.put(name + ".B", toDoubles(b));
            shapes.put(name + ".A", new long[]{a.length, a.length > 0 ? a[0].length : 0});
            shapes.put(name + ".B", new long[]{b.length, b.length > 0 ? b[0].length : 0});
        }
        try {
            io.cortexj.train.distributed.CheckpointShards.saveWithShapes(
                    state, shapes, directory, 1);
        } catch (IOException e) {
            throw new CortexJException("CORTEXJ-TRAIN-0111",
                    "checkpoint save failed: " + e.getMessage(),
                    "Check the output directory permissions.");
        }
    }

    /** Loads A/B from a checkpoint into an already-initialized block; null is a no-op. */
    void restoreFromCheckpoint(java.nio.file.Path directory) {
        if (directory == null) {
            return;
        }
        Map<String, double[]> state;
        Map<String, long[]> shapes;
        try {
            state = io.cortexj.train.distributed.CheckpointShards.load(directory);
            shapes = io.cortexj.train.distributed.CheckpointShards.shapesOf(directory);
        } catch (IOException e) {
            throw new CortexJException("CORTEXJ-TRAIN-0111",
                    "checkpoint is not readable: " + directory,
                    "Point resumeFrom at a checkpoint directory from a previous run.");
        }
        for (Map.Entry<String, LoraLinear> entry : block.loraChildren().entrySet()) {
            String name = entry.getKey();
            double[] a = state.get(name + ".A");
            double[] b = state.get(name + ".B");
            if (a == null || b == null || !shapes.containsKey(name + ".A")) {
                CortexJException failure = new CortexJException("CORTEXJ-TRAIN-0111",
                        "checkpoint does not cover the LoRA module " + name,
                        "Resume with the same LoRA target modules as the checkpoint.");
                failure.detail("module", name);
                throw failure;
            }
            entry.getValue().loadLora(toFloats(a), toFloats(b));
        }
    }

    void close() {
        trainer.close();
        model.close();
    }

    private NDArray stepLoss(LmBatcher.LmBatch batch, int accumulation) {
        // batch arrays live on the model manager (parameters and frozen
        // weights live there too)
        NDManager manager = model.getNDManager();
        int rows = batch.ids.length;
        int width = batch.sequenceLength;
        NDArray ids = manager.create(flattenInts(batch.ids), new Shape(rows, width));
        NDArray labels = manager.create(flattenLongs(batch.labels), new Shape(rows, width));
        NDArray mask = manager.create(flattenFloats(batch.mask), new Shape(rows, width));
        // the forward MUST run inside the collector: PtGradientCollector
        // enables torch grad mode on open, and a forward outside of it
        // builds a grad-less graph (same pattern as DjlTrainingBackend)
        try (GradientCollector gc = trainer.newGradientCollector()) {
            NDList logits = trainer.forward(new NDList(ids));
            NDArray lossValue = loss.evaluate(new NDList(labels, mask), logits);
            gc.backward(lossValue.div(accumulation));
            return lossValue;
        }
    }

    private double evalLoss(List<LmBatcher.LmBatch> batches) {
        NDManager manager = model.getNDManager();
        double weighted = 0;
        double weights = 0;
        for (LmBatcher.LmBatch batch : batches) {
            int rows = batch.ids.length;
            int width = batch.sequenceLength;
            NDArray ids = manager.create(flattenInts(batch.ids), new Shape(rows, width));
            NDArray labels = manager.create(flattenLongs(batch.labels), new Shape(rows, width));
            NDArray mask = manager.create(flattenFloats(batch.mask), new Shape(rows, width));
            NDList logits = trainer.evaluate(new NDList(ids));
            double value = loss.evaluate(new NDList(labels, mask), logits).getFloat();
            double weight = 0;
            for (float[] row : batch.mask) {
                for (float v : row) {
                    weight += v;
                }
            }
            weighted += value * weight;
            weights += weight;
        }
        return weights == 0 ? Double.NaN : weighted / weights;
    }

    private static double validTokens(LmBatcher.LmBatch batch) {
        double total = 0;
        for (float[] row : batch.mask) {
            for (float v : row) {
                total += v;
            }
        }
        return total;
    }

    private static int[] flattenInts(int[][] values) {
        int width = values.length > 0 ? values[0].length : 0;
        int[] flat = new int[values.length * width];
        for (int i = 0; i < values.length; i++) {
            System.arraycopy(values[i], 0, flat, i * width, width);
        }
        return flat;
    }

    private static long[] flattenLongs(int[][] values) {
        int width = values.length > 0 ? values[0].length : 0;
        long[] flat = new long[values.length * width];
        for (int i = 0; i < values.length; i++) {
            for (int t = 0; t < width; t++) {
                flat[i * width + t] = values[i][t];
            }
        }
        return flat;
    }

    private static float[] flattenFloats(float[][] values) {
        int width = values.length > 0 ? values[0].length : 0;
        float[] flat = new float[values.length * width];
        for (int i = 0; i < values.length; i++) {
            System.arraycopy(values[i], 0, flat, i * width, width);
        }
        return flat;
    }

    private static double[] toDoubles(float[][] matrix) {
        int columns = matrix.length > 0 ? matrix[0].length : 0;
        double[] flat = new double[matrix.length * columns];
        for (int r = 0; r < matrix.length; r++) {
            for (int c = 0; c < columns; c++) {
                flat[r * columns + c] = matrix[r][c];
            }
        }
        return flat;
    }

    private static float[] toFloats(double[] values) {
        float[] flat = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            flat[i] = (float) values[i];
        }
        return flat;
    }
}
