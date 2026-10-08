package io.cortexj.train.peft;

/**
 * One trained LoRA module: the low-rank matrices (A: rank x input,
 * B: output x rank) plus the config. Merge produces
 * {@code W' = W + (alpha/rank) * B * A} without touching the base
 * weights. Immutable.
 */
public final class LoRAAdapter {

    private final String moduleName;
    private final int inputSize;
    private final int outputSize;
    private final LoRAConfig config;
    private final float[][] matrixA;
    private final float[][] matrixB;

    public LoRAAdapter(String moduleName, int inputSize, int outputSize,
                       LoRAConfig config, float[][] matrixA, float[][] matrixB) {
        if (moduleName == null || moduleName.isEmpty()) {
            throw new IllegalArgumentException("module name must not be empty");
        }
        if (inputSize < 1 || outputSize < 1) {
            throw new IllegalArgumentException("input and output sizes must be positive");
        }
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        if (matrixA == null || matrixA.length != config.rank()
                || (matrixA.length > 0 && matrixA[0].length != inputSize)) {
            throw new IllegalArgumentException("matrix A must be rank x input");
        }
        if (matrixB == null || matrixB.length != outputSize
                || (matrixB.length > 0 && matrixB[0].length != config.rank())) {
            throw new IllegalArgumentException("matrix B must be output x rank");
        }
        this.moduleName = moduleName;
        this.inputSize = inputSize;
        this.outputSize = outputSize;
        this.config = config;
        this.matrixA = copy(matrixA);
        this.matrixB = copy(matrixB);
    }

    public String moduleName() {
        return moduleName;
    }

    public int inputSize() {
        return inputSize;
    }

    public int outputSize() {
        return outputSize;
    }

    public LoRAConfig config() {
        return config;
    }

    public float[][] matrixA() {
        return copy(matrixA);
    }

    public float[][] matrixB() {
        return copy(matrixB);
    }

    /**
     * Merges this adapter into the base weight matrix (output x input),
     * returning a new matrix: {@code W' = W + (alpha/rank) * B * A}.
     */
    public float[][] merge(float[][] baseWeights) {
        if (baseWeights == null || baseWeights.length != outputSize
                || (baseWeights.length > 0 && baseWeights[0].length != inputSize)) {
            throw new IllegalArgumentException(
                    "base weights must be output x input (" + outputSize + "x" + inputSize + ")");
        }
        double scale = config.scaling();
        float[][] merged = copy(baseWeights);
        for (int o = 0; o < outputSize; o++) {
            for (int r = 0; r < config.rank(); r++) {
                float bValue = matrixB[o][r];
                if (bValue == 0.0f) {
                    continue;
                }
                for (int i = 0; i < inputSize; i++) {
                    merged[o][i] += (float) (scale * bValue * matrixA[r][i]);
                }
            }
        }
        return merged;
    }

    private static float[][] copy(float[][] matrix) {
        float[][] copy = new float[matrix.length][];
        for (int i = 0; i < matrix.length; i++) {
            copy[i] = matrix[i].clone();
        }
        return copy;
    }
}
