package io.cortexj.train.djl.models;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.nn.AbstractBlock;
import ai.djl.nn.core.Linear;
import ai.djl.training.ParameterStore;
import ai.djl.util.PairList;
import java.util.Random;

/**
 * One LoRA-wrapped projection: y = frozen(Wx + b) + scaling * B(Ax).
 * A/B are child Linear blocks (the only trainable parameters of the
 * whole model); the frozen base weight is a plain NDArray (no
 * autograd). A ~ N(0, 0.02), B = 0, so the wrapped module starts
 * exactly identical to the base (verified by LoraLinearTest).
 */
public final class LoraLinear extends AbstractBlock {

    private final Linear a;
    private final Linear b;
    private final int inUnits;
    private final int outUnits;
    private final int rank;
    private final float scaling;
    private NDArray frozenWeight; // (in, out), transposed at load
    private NDArray frozenBias;    // (out) or null

    public LoraLinear(int inUnits, int outUnits, int rank, float scaling) {
        this.inUnits = inUnits;
        this.outUnits = outUnits;
        this.rank = rank;
        this.scaling = scaling;
        a = addChildBlock("lora_a", Linear.builder().setUnits(rank).optBias(false).build());
        b = addChildBlock("lora_b", Linear.builder().setUnits(outUnits).optBias(false).build());
    }

    public int inUnits() {
        return inUnits;
    }

    public int outUnits() {
        return outUnits;
    }

    public int rank() {
        return rank;
    }

    public float scaling() {
        return scaling;
    }

    /** Loads the frozen base weight (out x in row-major HF layout) and optional bias. */
    public void loadFrozen(float[] weightOutIn, float[] biasOrNull, NDManager manager) {
        float[] transposed = new float[inUnits * outUnits];
        for (int o = 0; o < outUnits; o++) {
            for (int i = 0; i < inUnits; i++) {
                transposed[i * outUnits + o] = weightOutIn[o * inUnits + i];
            }
        }
        frozenWeight = manager.create(transposed, new Shape(inUnits, outUnits));
        frozenBias = biasOrNull == null ? null
                : manager.create(biasOrNull, new Shape(outUnits));
    }

    /**
     * Deterministic LoRA init after trainer.initialize: A ~ N(0, 0.02)
     * from the seeded RNG, B = 0. Uses the proven set-after-initialize
     * pattern (DjlTrainingBackend.resumeBlock).
     */
    public void initLora(long seed) {
        Random random = new Random(seed);
        float[] aValues = new float[rank * inUnits];
        for (int i = 0; i < aValues.length; i++) {
            aValues[i] = (float) (random.nextGaussian() * 0.02);
        }
        a.getParameters().values().get(0).getArray().set(aValues);
        b.getParameters().values().get(0).getArray().set(new float[outUnits * rank]);
    }

    /** Restores trained A/B from a checkpoint (resume path). */
    public void loadLora(float[] aFlat, float[] bFlat) {
        a.getParameters().values().get(0).getArray().set(aFlat);
        b.getParameters().values().get(0).getArray().set(bFlat);
    }

    /** Trained A as (rank x in) for the LoRAAdapter artifact. */
    public float[][] matrixA() {
        float[] flat = a.getParameters().values().get(0).getArray().toFloatArray();
        return rows(flat, rank, inUnits);
    }

    /** Trained B as (out x rank) for the LoRAAdapter artifact. */
    public float[][] matrixB() {
        float[] flat = b.getParameters().values().get(0).getArray().toFloatArray();
        return rows(flat, outUnits, rank);
    }

    @Override
    protected NDList forwardInternal(ParameterStore parameterStore, NDList inputs,
                                     boolean training, PairList<String, Object> params) {
        NDArray x = inputs.singletonOrThrow();
        NDArray base = x.matMul(frozenWeight);
        if (frozenBias != null) {
            base = base.add(frozenBias);
        }
        NDArray delta = b.forward(parameterStore,
                a.forward(parameterStore, new NDList(x), training), training).singletonOrThrow();
        return new NDList(base.add(delta.mul(scaling)));
    }

    @Override
    public Shape[] getOutputShapes(Shape[] inputShapes) {
        return new Shape[]{new Shape(inputShapes[0].get(0), inputShapes[0].get(1), outUnits)};
    }

    @Override
    protected void initializeChildBlocks(NDManager manager, DataType dataType, Shape... inputShapes) {
        a.initialize(manager, dataType, new Shape(1, inUnits));
        b.initialize(manager, dataType, new Shape(1, rank));
    }

    private static float[][] rows(float[] flat, int rows, int columns) {
        float[][] matrix = new float[rows][columns];
        for (int r = 0; r < rows && r * columns < flat.length; r++) {
            System.arraycopy(flat, r * columns, matrix[r], 0,
                    Math.min(columns, flat.length - r * columns));
        }
        return matrix;
    }
}
