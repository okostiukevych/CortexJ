package io.cortexj.train.data;

import io.cortexj.error.CortexJException;
import io.cortexj.train.LmDataset;
import io.cortexj.train.LmExample;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LmDatasetsTest {

    @TempDir
    Path temp;

    /** a->1, b->2, ... exact tokenization is the DjlHfTokenizer's job; this is deterministic. */
    private final Tokenizer letters = new Tokenizer() {
        @Override
        public int[] encode(String text) {
            int[] ids = new int[text.length()];
            for (int i = 0; i < text.length(); i++) {
                ids[i] = text.charAt(i) - 'a' + 1;
            }
            return ids;
        }

        @Override
        public int vocabSize() {
            return 27;
        }
    };

    @Test
    void rawTextCorpusTrainsEveryToken() throws IOException {
        Path file = temp.resolve("text.jsonl");
        Files.write(file, "{\"text\": \"ab\"}\n{\"text\": \"cd\"}\n".getBytes());
        LmDataset dataset = LmDatasets.jsonlText(file, letters, 32);
        assertEquals(2, dataset.examples().size());
        assertArrayEquals(new int[]{1, 2}, dataset.examples().get(0).tokens());
        assertArrayEquals(new int[]{1, 1}, dataset.examples().get(0).lossMask());
        assertArrayEquals(new int[]{3, 4}, dataset.examples().get(1).tokens());
        assertEquals(27, dataset.vocabSize());
        assertTrue(dataset.fingerprint().startsWith("sha256:"));
    }

    @Test
    void sftCorpusMasksThePrompt() throws IOException {
        Path file = temp.resolve("sft.jsonl");
        Files.write(file,
                "{\"prompt\": \"ab\", \"response\": \"cd\"}\n".getBytes());
        LmDataset dataset = LmDatasets.jsonlSft(file, letters, 32);
        LmExample example = dataset.examples().get(0);
        assertArrayEquals(new int[]{1, 2, 3, 4}, example.tokens());
        assertArrayEquals(new int[]{0, 0, 1, 1}, example.lossMask());
    }

    @Test
    void longExamplesAreTruncatedToMaxSeqLen() throws IOException {
        Path file = temp.resolve("long.jsonl");
        Files.write(file, "{\"text\": \"abcdefgh\"}\n".getBytes());
        LmDataset dataset = LmDatasets.jsonlText(file, letters, 4);
        assertArrayEquals(new int[]{1, 2, 3, 4}, dataset.examples().get(0).tokens());
    }

    @Test
    void malformedLineFailsTypedWithTheLineNumber() throws IOException {
        Path file = temp.resolve("bad.jsonl");
        Files.write(file, "{\"text\": \"ab\"}\nnot json\n".getBytes());
        CortexJException failure = assertThrows(CortexJException.class,
                () -> LmDatasets.jsonlText(file, letters, 32));
        assertEquals("CORTEXJ-TRAIN-0110", failure.code());
        assertTrue(failure.toString().contains("2"), "line number must be reported");
    }

    @Test
    void fingerprintDistinguishesMasks() {
        List<LmExample> a = new ArrayList<LmExample>();
        a.add(LmExample.of(new int[]{1, 2}, new int[]{0, 1}));
        List<LmExample> b = new ArrayList<LmExample>();
        b.add(LmExample.of(new int[]{1, 2}, new int[]{1, 1}));
        assertNotEquals(LmDatasets.fingerprint(a), LmDatasets.fingerprint(b));
        assertEquals(LmDatasets.fingerprint(a), LmDatasets.fingerprint(a));
    }

    @Test
    void splitRespectsFractionAndIsSeedDeterministic() {
        List<LmExample> examples = new ArrayList<LmExample>();
        for (int i = 0; i < 10; i++) {
            examples.add(LmExample.of(new int[]{i, i + 1}, new int[]{1, 1}));
        }
        List<List<LmExample>> split = LmDatasets.split(examples, 0.9, 42L);
        assertEquals(9, split.get(0).size());
        assertEquals(1, split.get(1).size());
        List<List<LmExample>> again = LmDatasets.split(examples, 0.9, 42L);
        assertEquals(split.get(0).get(0).tokens()[0], again.get(0).get(0).tokens()[0]);
        assertEquals(split.get(1).get(0).tokens()[0], again.get(1).get(0).tokens()[0]);
    }
}
