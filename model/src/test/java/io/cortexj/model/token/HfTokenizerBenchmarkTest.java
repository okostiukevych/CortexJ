package io.cortexj.model.token;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("perf")
class HfTokenizerBenchmarkTest {

    @TempDir
    Path tempDir;

    /**
     * A realistic BPE tokenizer shape: ~50K merge rules (real HF vocabularies
     * carry ~50K), almost none of which apply to any given word - exactly the
     * case where the rules scan used to dominate encode() (audit M-19).
     */
    private String realisticBpeJson() {
        StringBuilder vocab = new StringBuilder();
        String[] alphabet = "abcdefghijklmnopqrstuvwxyz".split("");
        for (int i = 0; i < alphabet.length; i++) {
            if (i > 0) {
                vocab.append(',');
            }
            vocab.append('"').append(alphabet[i]).append("\":").append(i);
        }
        int nextId = alphabet.length;
        StringBuilder merges = new StringBuilder();
        for (int i = 0; i < 50_000; i++) {
            String first = "w" + (i % 500);
            String second = "v" + ((i * 7) % 500);
            if (i < 20) {
                // a few rules that actually apply to the corpus
                first = alphabet[i % alphabet.length];
                second = alphabet[(i + 1) % alphabet.length];
            }
            if (i > 0) {
                merges.append(',');
            }
            merges.append('"').append(first).append(' ').append(second).append('"');
            vocab.append(",\"").append(first).append(second).append("\":").append(nextId++);
        }
        return "{\"model\":{\"type\":\"BPE\",\"vocab\":{" + vocab
                + "},\"merges\":[" + merges + "]}}";
    }

    private static String corpus(int words) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < words; i++) {
            builder.append("tokenization performance budget word").append(i).append(' ');
        }
        return builder.toString();
    }

    @Test
    void encodeOnRealisticVocabularyHitsTheDocumentedBudget() throws IOException {
        Path file = tempDir.resolve("tokenizer.json");
        Files.write(file, realisticBpeJson().getBytes(StandardCharsets.UTF_8));
        HfTokenizer.TokenEncoder encoder = HfTokenizer.load(file);

        String text = corpus(100);
        long started = System.nanoTime();
        int[] ids = encoder.encode(text);
        long millis = (System.nanoTime() - started) / 1_000_000L;

        assertTrue(ids.length > 0, "the encode must produce tokens");
        System.out.println("BPE encode of a 100-word prompt against a 50K-merge vocabulary: "
                + millis + " ms");
        assertTrue(millis < 5_000L,
                "coarse budget: a 100-word prompt against a 50K-merge vocabulary must encode "
                        + "in bounded time (took " + millis + " ms)");
    }
}
