package io.cortexj.train.djl.models;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cortexj.error.CortexJException;
import io.cortexj.train.data.Tokenizer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * HF-exact tokenizer over the DJL native binding. The pure-Java
 * HfTokenizer is forbidden in the training path: its pre-tokenizer
 * differs and would silently mis-tokenize (no fake compatibility).
 * Unavailable natives fail typed (0105).
 */
public final class DjlHfTokenizer implements Tokenizer {

    private final HuggingFaceTokenizer delegate;
    private final int vocabSize;

    private DjlHfTokenizer(HuggingFaceTokenizer delegate, int vocabSize) {
        this.delegate = delegate;
        this.vocabSize = vocabSize;
    }

    public static DjlHfTokenizer load(Path tokenizerJson) {
        int vocab;
        try {
            vocab = vocabCount(tokenizerJson);
        } catch (CortexJException counted) {
            if ("CORTEXJ-TRAIN-0106".equals(counted.code())) {
                throw counted;
            }
            throw unavailable(tokenizerJson, counted);
        }
        try {
            return new DjlHfTokenizer(HuggingFaceTokenizer.newInstance(tokenizerJson), vocab);
        } catch (IOException | RuntimeException | UnsatisfiedLinkError | NoClassDefFoundError e) {
            throw unavailable(tokenizerJson, e);
        }
    }

    private static CortexJException unavailable(Path tokenizerJson, Throwable cause) {
        CortexJException failure = new CortexJException("CORTEXJ-TRAIN-0105",
                "exact HF tokenizer unavailable: " + cause,
                "Add ai.djl.huggingface:tokenizers (with natives) to the assembly; "
                        + "the inexact pure-Java fallback is forbidden for training.");
        failure.detail("file", tokenizerJson.toString());
        return failure;
    }

    @Override
    public int[] encode(String text) {
        long[] ids = delegate.encode(text).getIds();
        int[] values = new int[ids.length];
        for (int i = 0; i < ids.length; i++) {
            values[i] = (int) ids[i];
        }
        return values;
    }

    @Override
    public int vocabSize() {
        return vocabSize;
    }

    /** Decodes ids back to text (parity lane compares continuations as strings). */
    public String decode(int[] ids) {
        long[] values = new long[ids.length];
        for (int i = 0; i < ids.length; i++) {
            values[i] = ids[i];
        }
        return delegate.decode(values, false);
    }

    /** Counts tokenizer.json vocab entries (object or [piece, score] array). */
    public static int vocabCount(Path tokenizerJson) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode vocab = mapper.readTree(Files.newInputStream(tokenizerJson))
                    .path("model").path("vocab");
            if (vocab.isObject()) {
                return vocab.size();
            }
            if (vocab.isArray()) {
                return vocab.size();
            }
            throw new CortexJException("CORTEXJ-TRAIN-0106",
                    "tokenizer.json carries no vocab",
                    "Ship a standard HuggingFace tokenizer.json.");
        } catch (IOException e) {
            throw new CortexJException("CORTEXJ-TRAIN-0105",
                    "tokenizer.json is not readable: " + e.getMessage(),
                    "Ship a standard HuggingFace tokenizer.json.");
        }
    }
}
