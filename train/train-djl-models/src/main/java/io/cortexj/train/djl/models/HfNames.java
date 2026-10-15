package io.cortexj.train.djl.models;

import java.util.ArrayList;
import java.util.List;

/** Standard HF tensor names for the supported families. */
final class HfNames {

    static List<String> expected(ArchitectureConfig config) {
        List<String> names = new ArrayList<String>();
        names.add("model.embed_tokens.weight");
        for (int layer = 0; layer < config.numLayers(); layer++) {
            names.add("model.layers." + layer + ".self_attn.q_proj.weight");
            names.add("model.layers." + layer + ".self_attn.k_proj.weight");
            names.add("model.layers." + layer + ".self_attn.v_proj.weight");
            names.add("model.layers." + layer + ".self_attn.o_proj.weight");
            if (config.attentionBias()) {
                names.add("model.layers." + layer + ".self_attn.q_proj.bias");
                names.add("model.layers." + layer + ".self_attn.k_proj.bias");
                names.add("model.layers." + layer + ".self_attn.v_proj.bias");
            }
            if (config.qkNorm()) {
                names.add("model.layers." + layer + ".self_attn.q_norm.weight");
                names.add("model.layers." + layer + ".self_attn.k_norm.weight");
            }
            names.add("model.layers." + layer + ".mlp.gate_proj.weight");
            names.add("model.layers." + layer + ".mlp.up_proj.weight");
            names.add("model.layers." + layer + ".mlp.down_proj.weight");
            names.add("model.layers." + layer + ".input_layernorm.weight");
            names.add("model.layers." + layer + ".post_attention_layernorm.weight");
        }
        names.add("model.norm.weight");
        if (!config.tieEmbeddings()) {
            names.add("lm_head.weight");
        }
        return names;
    }

    private HfNames() {
    }
}
