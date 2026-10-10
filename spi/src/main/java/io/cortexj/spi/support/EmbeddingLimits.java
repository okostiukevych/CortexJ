package io.cortexj.spi.support;

import io.cortexj.ErrorCode;
import io.cortexj.error.InferenceException;

import java.util.List;

/**
 * Shared input bounds for embedding providers (ONNX, DJL).
 *
 * <p>Audit 2026-10-10: unbounded inputs turn one {@code embed()} call into
 * hundreds of MB of heap (vectors plus per-text engine transients) with
 * strictly sequential inference. Providers refuse oversized calls with a
 * typed error instead of risking an OOM. Tune via
 * {@code cortexj.embed.maxTexts} / {@code CORTEXJ_EMBED_MAX_TEXTS} and
 * {@code cortexj.embed.maxCharsPerText} /
 * {@code CORTEXJ_EMBED_MAX_CHARS_PER_TEXT}.
 */
public final class EmbeddingLimits {

    private static final int DEFAULT_MAX_TEXTS = 4096;
    private static final int DEFAULT_MAX_CHARS_PER_TEXT = 1000000;

    private EmbeddingLimits() {
    }

    public static void checkEmbedLimits(List<String> texts) {
        int maxTexts = limit("cortexj.embed.maxTexts",
                "CORTEXJ_EMBED_MAX_TEXTS", DEFAULT_MAX_TEXTS);
        if (texts.size() > maxTexts) {
            throw new InferenceException(ErrorCode.INFERENCE_FAILED,
                    "Embedding request has " + texts.size() + " texts, exceeding the cap of "
                            + maxTexts + " per call.",
                    "Split the input into batches of at most " + maxTexts
                            + " texts, or raise cortexj.embed.maxTexts / CORTEXJ_EMBED_MAX_TEXTS.");
        }
        int maxChars = limit("cortexj.embed.maxCharsPerText",
                "CORTEXJ_EMBED_MAX_CHARS_PER_TEXT", DEFAULT_MAX_CHARS_PER_TEXT);
        for (String text : texts) {
            if (text != null && text.length() > maxChars) {
                throw new InferenceException(ErrorCode.INFERENCE_FAILED,
                        "Embedding input has " + text.length() + " characters, exceeding the cap of "
                                + maxChars + " per text.",
                        "Split long inputs, or raise cortexj.embed.maxCharsPerText / "
                                + "CORTEXJ_EMBED_MAX_CHARS_PER_TEXT.");
            }
        }
    }

    static int limit(String property, String env, int def) {
        String configured = System.getProperty(property);
        if (configured == null || configured.trim().isEmpty()) {
            configured = System.getenv(env);
        }
        if (configured == null || configured.trim().isEmpty()) {
            return def;
        }
        try {
            long parsed = Long.parseLong(configured.trim());
            return parsed > 0 && parsed <= Integer.MAX_VALUE ? (int) parsed : def;
        } catch (NumberFormatException invalid) {
            return def;
        }
    }
}
