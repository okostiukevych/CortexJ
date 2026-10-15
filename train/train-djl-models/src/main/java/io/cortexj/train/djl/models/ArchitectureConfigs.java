package io.cortexj.train.djl.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cortexj.error.CortexJException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** config.json parsing into {@link ArchitectureConfig} with typed failures. */
public final class ArchitectureConfigs {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ArchitectureConfig parse(Path configJson) {
        try {
            return fromJson(new String(Files.readAllBytes(configJson), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new CortexJException("CORTEXJ-TRAIN-0102",
                    "config.json is not readable: " + configJson,
                    "Ship a standard HuggingFace config.json.");
        }
    }

    public static ArchitectureConfig fromJson(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new CortexJException("CORTEXJ-TRAIN-0102",
                    "config.json is not valid JSON: " + e.getMessage(),
                    "Ship a standard HuggingFace config.json.");
        }
        String architecture = firstArchitecture(root);
        DecoderFamily family = DecoderFamily.ofArchitecture(architecture);
        List<String> missing = new ArrayList<String>();
        int hiddenSize = requiredInt(root, "hidden_size", missing);
        int numLayers = requiredInt(root, "num_hidden_layers", missing);
        int numHeads = requiredInt(root, "num_attention_heads", missing);
        int intermediateSize = requiredInt(root, "intermediate_size", missing);
        int vocabSize = requiredInt(root, "vocab_size", missing);
        if (!missing.isEmpty()) {
            CortexJException failure = new CortexJException("CORTEXJ-TRAIN-0102",
                    "config.json is missing required fields: " + missing,
                    "Ship a standard HuggingFace config.json with every required field.");
            failure.detail("missing", missing.toString());
            failure.detail("architecture", architecture);
            throw failure;
        }
        int numKvHeads = root.path("num_key_value_heads").asInt(numHeads);
        // Qwen3-0.6B class carries head_dim != hidden/heads (128 vs 64):
        // the explicit field wins, the quotient is the fallback
        int headDim = root.path("head_dim").asInt(hiddenSize / numHeads);
        double ropeTheta = root.path("rope_theta").asDouble(10000.0);
        double rmsEps = root.path("rms_norm_eps").asDouble(1e-6);
        boolean tie = root.path("tie_word_embeddings").asBoolean(false);
        int maxPositions = root.path("max_position_embeddings").asInt(4096);
        return new ArchitectureConfig(family, hiddenSize, numLayers, numHeads,
                numKvHeads, headDim, intermediateSize, vocabSize, ropeTheta, rmsEps, tie,
                Math.max(maxPositions, 512));
    }

    /**
     * Parameter count for the memory estimate: embedding, per-layer
     * attention (q/k/v/o), SwiGLU MLP, norms, QK-norm where present,
     * and the untied head when present. Documented and honest -
     * activations are estimated separately by the backend.
     */
    public static long paramCount(ArchitectureConfig config) {
        long hidden = config.hiddenSize();
        long kvDim = config.kvDim();
        long perLayer = hidden * hidden
                + hidden * kvDim * 2
                + hidden * hidden
                + hidden * config.intermediateSize() * 3
                + hidden * 2;
        if (config.qkNorm()) {
            perLayer += config.headDim() * 2;
        }
        long total = (long) config.vocabSize() * hidden
                + (long) config.numLayers() * perLayer
                + hidden;
        if (!config.tieEmbeddings()) {
            total += (long) config.vocabSize() * hidden;
        }
        return total;
    }

    private static String firstArchitecture(JsonNode root) {
        JsonNode architectures = root.path("architectures");
        if (architectures.isArray() && architectures.size() > 0
                && !architectures.get(0).asText("").trim().isEmpty()) {
            return architectures.get(0).asText();
        }
        CortexJException failure = new CortexJException("CORTEXJ-TRAIN-0101",
                "config.json carries no architectures entry",
                "Ship a standard HuggingFace config.json.");
        failure.detail("families", "Qwen2ForCausalLM Qwen2.5ForCausalLM "
                + "Qwen3ForCausalLM LlamaForCausalLM MistralForCausalLM");
        throw failure;
    }

    private static int requiredInt(JsonNode root, String field, List<String> missing) {
        JsonNode node = root.get(field);
        if (node == null || !node.canConvertToInt()) {
            missing.add(field);
            return 0;
        }
        return node.asInt();
    }

    private ArchitectureConfigs() {
    }
}
