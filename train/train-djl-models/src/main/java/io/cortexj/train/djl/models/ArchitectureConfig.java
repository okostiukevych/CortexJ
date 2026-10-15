package io.cortexj.train.djl.models;

/** Immutable architecture description for one decoder base model. */
public final class ArchitectureConfig {

    private final DecoderFamily family;
    private final int hiddenSize;
    private final int numLayers;
    private final int numHeads;
    private final int numKvHeads;
    private final int headDim;
    private final int intermediateSize;
    private final int vocabSize;
    private final double ropeTheta;
    private final double rmsEps;
    private final boolean tieEmbeddings;
    private final int maxPositions;

    ArchitectureConfig(DecoderFamily family, int hiddenSize, int numLayers, int numHeads,
                       int numKvHeads, int headDim, int intermediateSize, int vocabSize,
                       double ropeTheta, double rmsEps, boolean tieEmbeddings, int maxPositions) {
        this.family = family;
        this.hiddenSize = hiddenSize;
        this.numLayers = numLayers;
        this.numHeads = numHeads;
        this.numKvHeads = numKvHeads;
        this.headDim = headDim;
        this.intermediateSize = intermediateSize;
        this.vocabSize = vocabSize;
        this.ropeTheta = ropeTheta;
        this.rmsEps = rmsEps;
        this.tieEmbeddings = tieEmbeddings;
        this.maxPositions = maxPositions;
    }

    public DecoderFamily family() {
        return family;
    }

    public int hiddenSize() {
        return hiddenSize;
    }

    public int numLayers() {
        return numLayers;
    }

    public int numHeads() {
        return numHeads;
    }

    public int numKvHeads() {
        return numKvHeads;
    }

    public int intermediateSize() {
        return intermediateSize;
    }

    public int vocabSize() {
        return vocabSize;
    }

    public double ropeTheta() {
        return ropeTheta;
    }

    public double rmsEps() {
        return rmsEps;
    }

    public boolean tieEmbeddings() {
        return tieEmbeddings;
    }

    public int maxPositions() {
        return maxPositions;
    }

    public boolean attentionBias() {
        return family.attentionBias();
    }

    public boolean qkNorm() {
        return family.qkNorm();
    }

    public int headDim() {
        return headDim;
    }

    public int kvHeadDim() {
        return headDim;
    }

    public int kvDim() {
        return numKvHeads * kvHeadDim();
    }
}
