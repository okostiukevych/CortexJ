package io.cortexj.convert.lora;

import io.cortexj.convert.gguf.internal.GgufDocument;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Llama-family tensor naming convention (ADR-029): convenience mapping
 * suggestions between GGUF tensor names and LoRA module names. The
 * caller still passes explicit mappings to the merge; this helper only
 * suggests, never decides.
 */
public final class LlamaConvention {

    private static final Pattern BLOCK_TENSOR =
            Pattern.compile("^blk\\.(\\d+)\\.(attn_q|attn_k|attn_v|attn_o|attn_output|ffn_gate|ffn_up|ffn_down)\\.weight$");

    private static final Map<String, String> MODULES = new LinkedHashMap<>();

    static {
        MODULES.put("attn_q", "q_proj");
        MODULES.put("attn_k", "k_proj");
        MODULES.put("attn_v", "v_proj");
        MODULES.put("attn_o", "o_proj");
        MODULES.put("attn_output", "o_proj");
        MODULES.put("ffn_gate", "gate_proj");
        MODULES.put("ffn_up", "up_proj");
        MODULES.put("ffn_down", "down_proj");
    }

    private LlamaConvention() {
    }

    /**
     * Suggests (tensorName, module) pairs for tensors matching the
     * llama-family block pattern. Tensors outside the pattern are
     * ignored (pass through untouched).
     */
    public static List<String[]> suggestMappings(GgufDocument document) {
        List<String[]> suggestions = new ArrayList<>();
        for (GgufDocument.TensorInfo tensor : document.tensors) {
            Matcher matcher = BLOCK_TENSOR.matcher(tensor.name);
            if (!matcher.matches()) {
                continue;
            }
            String module = MODULES.get(matcher.group(2));
            if (module != null) {
                suggestions.add(new String[]{tensor.name, "blk." + matcher.group(1) + "." + module});
            }
        }
        return suggestions;
    }

    /**
     * The module short name for a tensor, or null outside the pattern.
     */
    public static String moduleFor(String tensorName) {
        Matcher matcher = BLOCK_TENSOR.matcher(tensorName);
        return matcher.matches() ? MODULES.get(matcher.group(2)) : null;
    }
}
