package io.cortexj.train.djl.models;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.nn.AbstractBlock;
import ai.djl.training.ParameterStore;
import ai.djl.util.PairList;
import io.cortexj.error.CortexJException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The generic Llama-family decoder as one DJL block. Frozen weights
 * are plain NDArrays created at load time; LoRA wrappers are the only
 * child blocks (and so the only trainable parameters the trainer
 * sees). Embedding lookup runs in pure Java over the weight float[].
 */
public final class GenericDecoderBlock extends AbstractBlock {

    private final ArchitectureConfig config;
    private NDManager manager;
    private final DecoderLayer[] layers;
    private final Map<String, LoraLinear> lora;
    private float[] embedding;
    private NDArray headWeight; // (hidden, vocab)
    private NDArray finalNorm;
    private NDArray cosTable;
    private NDArray sinTable;
    private boolean loaded;

    private GenericDecoderBlock(ArchitectureConfig config) {
        this.config = config;
        this.layers = new DecoderLayer[config.numLayers()];
        this.lora = new LinkedHashMap<String, LoraLinear>();
    }

    /**
     * Builds the block for one config. targetModules holds llama-style
     * short names (q_proj, k_proj, v_proj, o_proj, gate_proj, up_proj,
     * down_proj); rank/scaling configure every wrapper.
     */
    public static GenericDecoderBlock build(ArchitectureConfig config, Set<String> targetModules,
                                            int rank, float scaling) {
        GenericDecoderBlock block = new GenericDecoderBlock(config);
        for (int layer = 0; layer < config.numLayers(); layer++) {
            CausalGqaAttention attention = new CausalGqaAttention(config, layer,
                    block.wire("layers." + layer + ".self_attn.q_proj",
                            targetModules.contains("q_proj"), config.hiddenSize(),
                            config.hiddenSize(), rank, scaling),
                    block.wire("layers." + layer + ".self_attn.k_proj",
                            targetModules.contains("k_proj"), config.hiddenSize(),
                            config.kvDim(), rank, scaling),
                    block.wire("layers." + layer + ".self_attn.v_proj",
                            targetModules.contains("v_proj"), config.hiddenSize(),
                            config.kvDim(), rank, scaling),
                    block.wire("layers." + layer + ".self_attn.o_proj",
                            targetModules.contains("o_proj"), config.hiddenSize(),
                            config.hiddenSize(), rank, scaling));
            Mlp mlp = new Mlp(config,
                    block.wire("layers." + layer + ".mlp.gate_proj",
                            targetModules.contains("gate_proj"), config.hiddenSize(),
                            config.intermediateSize(), rank, scaling),
                    block.wire("layers." + layer + ".mlp.up_proj",
                            targetModules.contains("up_proj"), config.hiddenSize(),
                            config.intermediateSize(), rank, scaling),
                    block.wire("layers." + layer + ".mlp.down_proj",
                            targetModules.contains("down_proj"), config.intermediateSize(),
                            config.hiddenSize(), rank, scaling));
            block.layers[layer] = new DecoderLayer(config, attention, mlp);
        }
        return block;
    }

    /** Loads frozen weights; missing/extra tensors fail typed with the diff (0103). */
    public void loadWeights(LoadedWeights weights, NDManager manager) {
        if (loaded) {
            throw new IllegalStateException("weights are already loaded");
        }
        this.manager = manager;
        List<String> expected = HfNames.expected(config);
        List<String> missing = new ArrayList<String>();
        List<String> unexpected = new ArrayList<String>();
        for (String name : expected) {
            if (!weights.has(name)) {
                missing.add(name);
            }
        }
        for (String name : weights.names()) {
            if (!expected.contains(name)) {
                unexpected.add(name);
            }
        }
        if (!missing.isEmpty() || !unexpected.isEmpty()) {
            CortexJException failure = new CortexJException("CORTEXJ-TRAIN-0103",
                    "base weight tensors do not match the architecture",
                    "Re-download the base model or use a supported family checkpoint.");
            failure.detail("missing", missing.toString());
            failure.detail("unexpected", unexpected.toString());
            throw failure;
        }
        embedding = weights.tensor("model.embed_tokens.weight");
        if (embedding.length != config.vocabSize() * config.hiddenSize()) {
            CortexJException failure = new CortexJException("CORTEXJ-TRAIN-0106",
                    "embedding matrix does not match vocab x hidden",
                    "The tokenizer, config.json and weights must agree.");
            failure.detail("expected", config.vocabSize() * config.hiddenSize() + " floats");
            failure.detail("actual", embedding.length + " floats");
            throw failure;
        }
        for (int layer = 0; layer < layers.length; layer++) {
            loadLayer(layer, weights);
        }
        finalNorm = manager.create(weights.tensor("model.norm.weight"),
                new Shape(config.hiddenSize()));
        float[] headSource = config.tieEmbeddings() ? embedding : weights.tensor("lm_head.weight");
        // transpose (vocab, hidden) -> (hidden, vocab) for the matmul
        float[] head = new float[config.hiddenSize() * config.vocabSize()];
        for (int v = 0; v < config.vocabSize(); v++) {
            for (int h = 0; h < config.hiddenSize(); h++) {
                head[h * config.vocabSize() + v] = headSource[v * config.hiddenSize() + h];
            }
        }
        headWeight = manager.create(head,
                new Shape(config.hiddenSize(), config.vocabSize()));
        int half = config.headDim() / 2;
        cosTable = manager.create(LmOps.ropeCos(config.maxPositions(), half, config.ropeTheta()),
                new Shape(config.maxPositions(), half));
        sinTable = manager.create(LmOps.ropeSin(config.maxPositions(), half, config.ropeTheta()),
                new Shape(config.maxPositions(), half));
        loaded = true;
    }

    /** Deterministic LoRA init (post trainer.initialize), distinct seed per wrapper. */
    public void initLora(long seed) {
        int index = 0;
        for (LoraLinear wrapper : lora.values()) {
            wrapper.initLora(seed + index);
            index++;
        }
    }

    public Map<String, LoraLinear> loraChildren() {
        return Collections.unmodifiableMap(lora);
    }

    public ArchitectureConfig config() {
        return config;
    }

    @Override
    protected NDList forwardInternal(ParameterStore parameterStore, NDList inputs,
                                     boolean training, PairList<String, Object> params) {
        if (!loaded) {
            throw new IllegalStateException("weights are not loaded");
        }
        NDArray ids = inputs.singletonOrThrow();
        Shape shape = ids.getShape();
        int n = (int) shape.get(0);
        int t = (int) shape.get(1);
        int hidden = config.hiddenSize();
        if (t > config.maxPositions()) {
            throw new IllegalArgumentException("sequence length " + t
                    + " exceeds the rope table " + config.maxPositions());
        }
        int[] idValues = ids.toIntArray();
        float[] embedded = new float[n * t * hidden];
        for (int b = 0; b < n; b++) {
            for (int p = 0; p < t; p++) {
                int id = idValues[b * t + p];
                if (id < 0 || id >= config.vocabSize()) {
                    throw new IllegalArgumentException("token id out of range: " + id);
                }
                System.arraycopy(embedding, id * hidden, embedded, (b * t + p) * hidden, hidden);
            }
        }
        NDArray x = manager.create(embedded, new Shape(n, t, hidden));
        int half = config.headDim() / 2;
        NDArray cos = cosTable.get("0:" + t).reshape(1, 1, t, half);
        NDArray sin = sinTable.get("0:" + t).reshape(1, 1, t, half);
        for (DecoderLayer layer : layers) {
            x = layer.forward(x, cos, sin, manager, parameterStore, training);
        }
        x = LmOps.rmsNorm(x, finalNorm, (float) config.rmsEps());
        return new NDList(x.matMul(headWeight));
    }

    @Override
    public Shape[] getOutputShapes(Shape[] inputShapes) {
        return new Shape[]{new Shape(inputShapes[0].get(0), inputShapes[0].get(1),
                config.vocabSize())};
    }

    @Override
    protected void initializeChildBlocks(NDManager manager, DataType dataType,
                                         Shape... inputShapes) {
        for (Map.Entry<String, LoraLinear> entry : lora.entrySet()) {
            entry.getValue().initialize(manager, dataType,
                    new Shape(1, entry.getValue().inUnits()));
        }
    }

    private LoraLinear wire(String childName, boolean wanted, int in, int out, int rank,
                            float scaling) {
        if (!wanted) {
            return null;
        }
        LoraLinear wrapper = new LoraLinear(in, out, rank, scaling);
        addChildBlock(childName, wrapper);
        lora.put(childName, wrapper);
        return wrapper;
    }

    private void loadLayer(int layer, LoadedWeights weights) {
        String prefix = "model.layers." + layer + ".";
        CausalGqaAttention attention = layers[layer].attention;
        attention.loadFrozen(
                weights.tensor(prefix + "self_attn.q_proj.weight"),
                weights.tensor(prefix + "self_attn.k_proj.weight"),
                weights.tensor(prefix + "self_attn.v_proj.weight"),
                weights.tensor(prefix + "self_attn.o_proj.weight"),
                config.qkNorm() ? weights.tensor(prefix + "self_attn.q_norm.weight") : null,
                config.qkNorm() ? weights.tensor(prefix + "self_attn.k_norm.weight") : null,
                manager);
        if (config.attentionBias()) {
            attention.loadBiases(
                    weights.tensor(prefix + "self_attn.q_proj.bias"),
                    weights.tensor(prefix + "self_attn.k_proj.bias"),
                    weights.tensor(prefix + "self_attn.v_proj.bias"),
                    manager);
        }
        Mlp mlp = layers[layer].mlp;
        mlp.loadFrozen(
                weights.tensor(prefix + "mlp.gate_proj.weight"),
                weights.tensor(prefix + "mlp.up_proj.weight"),
                weights.tensor(prefix + "mlp.down_proj.weight"),
                manager);
        // LoRA wrappers own the frozen weight of their module
        LoraLinear q = lora.get("layers." + layer + ".self_attn.q_proj");
        if (q != null) {
            q.loadFrozen(weights.tensor(prefix + "self_attn.q_proj.weight"),
                    config.attentionBias() ? weights.tensor(prefix + "self_attn.q_proj.bias") : null,
                    manager);
        }
        LoraLinear k = lora.get("layers." + layer + ".self_attn.k_proj");
        if (k != null) {
            k.loadFrozen(weights.tensor(prefix + "self_attn.k_proj.weight"),
                    config.attentionBias() ? weights.tensor(prefix + "self_attn.k_proj.bias") : null,
                    manager);
        }
        LoraLinear v = lora.get("layers." + layer + ".self_attn.v_proj");
        if (v != null) {
            v.loadFrozen(weights.tensor(prefix + "self_attn.v_proj.weight"),
                    config.attentionBias() ? weights.tensor(prefix + "self_attn.v_proj.bias") : null,
                    manager);
        }
        LoraLinear o = lora.get("layers." + layer + ".self_attn.o_proj");
        if (o != null) {
            o.loadFrozen(weights.tensor(prefix + "self_attn.o_proj.weight"), null, manager);
        }
        LoraLinear gate = lora.get("layers." + layer + ".mlp.gate_proj");
        if (gate != null) {
            gate.loadFrozen(weights.tensor(prefix + "mlp.gate_proj.weight"), null, manager);
        }
        LoraLinear up = lora.get("layers." + layer + ".mlp.up_proj");
        if (up != null) {
            up.loadFrozen(weights.tensor(prefix + "mlp.up_proj.weight"), null, manager);
        }
        LoraLinear down = lora.get("layers." + layer + ".mlp.down_proj");
        if (down != null) {
            down.loadFrozen(weights.tensor(prefix + "mlp.down_proj.weight"), null, manager);
        }
        layers[layer].loadFrozen(weights.tensor(prefix + "input_layernorm.weight"),
                weights.tensor(prefix + "post_attention_layernorm.weight"), manager);
    }
}
