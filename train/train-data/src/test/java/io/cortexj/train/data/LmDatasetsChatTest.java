package io.cortexj.train.data;

import io.cortexj.error.CortexJException;
import io.cortexj.train.LmDataset;
import io.cortexj.train.LmExample;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LmDatasetsChatTest {

    /** Deterministic word-per-id fake tokenizer; markers ride the split. */
    private static final Tokenizer TOKENIZER = new Tokenizer() {
        @Override
        public int[] encode(String text) {
            List<Integer> ids = new ArrayList<>();
            for (String word : text.trim().split("\\s+")) {
                if (!word.isEmpty()) {
                    ids.add(word.hashCode());
                }
            }
            int[] out = new int[ids.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = ids.get(i);
            }
            return out;
        }

        @Override
        public int vocabSize() {
            return 1024;
        }
    };

    @TempDir
    Path temp;

    @Test
    void chatLinesTrainOnTheAssistantTurnOnly() throws Exception {
        Path file = temp.resolve("chat.jsonl");
        Files.write(file, Arrays.asList(
                "{\"messages\":[{\"role\":\"system\",\"content\":\"be terse\"},"
                        + "{\"role\":\"user\",\"content\":\"hi\"},"
                        + "{\"role\":\"assistant\",\"content\":\"hello\"}]}",
                "{\"messages\":[{\"role\":\"user\",\"content\":\"again\"},"
                        + "{\"role\":\"assistant\",\"content\":\"ok\"}]}"));
        LmDataset dataset = LmDatasets.jsonlChat(file, TOKENIZER, 512, "qwen");
        assertEquals(2, dataset.examples().size());
        LmExample first = dataset.examples().get(0);
        ChatTemplates.Rendered rendered = ChatTemplates.render("qwen", Arrays.asList(
                new ChatTemplates.Message("system", "be terse"),
                new ChatTemplates.Message("user", "hi"),
                new ChatTemplates.Message("assistant", "hello")));
        int[] contextIds = TOKENIZER.encode(rendered.contextText());
        int[] responseIds = TOKENIZER.encode(rendered.responseText());
        assertEquals(contextIds.length + responseIds.length, first.tokens().length);
        for (int i = 0; i < contextIds.length; i++) {
            assertEquals(contextIds[i], first.tokens()[i]);
            assertEquals(0, first.lossMask()[i], "context is masked at " + i);
        }
        for (int i = 0; i < responseIds.length; i++) {
            assertEquals(responseIds[i], first.tokens()[contextIds.length + i]);
            assertEquals(1, first.lossMask()[contextIds.length + i],
                    "response carries loss at " + i);
        }
        // second line: no system message, still trains
        LmExample second = dataset.examples().get(1);
        int trained = 0;
        for (int m : second.lossMask()) {
            trained += m;
        }
        assertTrue(trained > 0, "the response must carry loss");
    }

    @Test
    void missingMessagesFieldFailsTypedWithTheLine() throws Exception {
        Path file = temp.resolve("bad.jsonl");
        Files.write(file, Arrays.asList("{\"prompt\":\"x\",\"response\":\"y\"}"));
        CortexJException failure = assertThrows(CortexJException.class,
                () -> LmDatasets.jsonlChat(file, TOKENIZER, 512, "qwen"));
        assertEquals("CORTEXJ-TRAIN-0112", failure.code());
        assertEquals("1", failure.details().get("line"));
    }

    @Test
    void unknownTemplatePropagatesTyped() throws Exception {
        Path file = temp.resolve("chat.jsonl");
        Files.write(file, Arrays.asList(
                "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"},"
                        + "{\"role\":\"assistant\",\"content\":\"hello\"}]}"));
        CortexJException failure = assertThrows(CortexJException.class,
                () -> LmDatasets.jsonlChat(file, TOKENIZER, 512, "nope"));
        assertEquals("CORTEXJ-TRAIN-0113", failure.code());
    }
}
