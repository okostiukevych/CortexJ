package io.cortexj.train.djl.models;

import io.cortexj.error.CortexJException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Supported decoder families. Knobs absent from config.json (attention
 * bias, QK-norm) live in the preset, keyed by the config.json
 * "architectures" value; unknown families fail typed listing the
 * supported ones (spec 140.4: no fake compatibility).
 */
public enum DecoderFamily {

    QWEN2(true, false),
    QWEN3(false, true),
    LLAMA(false, false);

    private static final Map<String, DecoderFamily> BY_ARCHITECTURE =
            new LinkedHashMap<String, DecoderFamily>();

    static {
        BY_ARCHITECTURE.put("Qwen2ForCausalLM", QWEN2);
        BY_ARCHITECTURE.put("Qwen2.5ForCausalLM", QWEN2);
        BY_ARCHITECTURE.put("Qwen3ForCausalLM", QWEN3);
        BY_ARCHITECTURE.put("LlamaForCausalLM", LLAMA);
        BY_ARCHITECTURE.put("MistralForCausalLM", LLAMA);
    }

    private final boolean attentionBias;
    private final boolean qkNorm;

    DecoderFamily(boolean attentionBias, boolean qkNorm) {
        this.attentionBias = attentionBias;
        this.qkNorm = qkNorm;
    }

    public boolean attentionBias() {
        return attentionBias;
    }

    public boolean qkNorm() {
        return qkNorm;
    }

    public static DecoderFamily ofArchitecture(String hfArchitecture) {
        DecoderFamily family = BY_ARCHITECTURE.get(hfArchitecture);
        if (family == null) {
            CortexJException failure = new CortexJException("CORTEXJ-TRAIN-0101",
                    "unsupported model architecture: " + hfArchitecture,
                    "Use one of the supported Llama-family architectures.");
            failure.detail("architecture", hfArchitecture);
            failure.detail("supported", BY_ARCHITECTURE.keySet().toString());
            throw failure;
        }
        return family;
    }
}
