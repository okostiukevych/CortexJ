package io.cortexj.train.djl.models;

import io.cortexj.error.CortexJException;
import io.cortexj.train.data.Tokenizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DjlHfTokenizerTest {

    @TempDir
    Path temp;

    @Test
    void vocabCountHandlesObjectAndArrayVocabs() throws Exception {
        Path objectVocab = temp.resolve("object-tokenizer.json");
        Files.write(objectVocab,
                "{\"model\":{\"type\":\"BPE\",\"vocab\":{\"a\":0,\"b\":1},\"merges\":[]}}"
                        .getBytes());
        assertEquals(2, DjlHfTokenizer.vocabCount(objectVocab));
        Path arrayVocab = temp.resolve("array-tokenizer.json");
        Files.write(arrayVocab,
                "{\"model\":{\"type\":\"Unigram\",\"vocab\":[[\"a\",-1.0],[\"b\",-2.0]]}}"
                        .getBytes());
        assertEquals(2, DjlHfTokenizer.vocabCount(arrayVocab));
    }

    @Test
    void missingTokenizerFailsTyped() {
        CortexJException failure = assertThrows(CortexJException.class,
                () -> DjlHfTokenizer.load(temp.resolve("nope.json")));
        assertEquals("CORTEXJ-TRAIN-0105", failure.code());
        assertTrue(failure.getMessage().contains("exact"));
    }

    @Test
    void realTokenizerEncodesWhenPresent() throws Exception {
        String env = System.getenv("CORTEXJ_TEST_TOKENIZER_JSON");
        assumeTrue(env != null && Files.isRegularFile(Path.of(env)),
                "CORTEXJ_TEST_TOKENIZER_JSON not set - exact-tokenizer contract skipped loudly");
        Tokenizer tokenizer = DjlHfTokenizer.load(Path.of(env));
        int[] ids = tokenizer.encode("hello world");
        assertTrue(ids.length > 0);
        assertTrue(tokenizer.vocabSize() > 0);
        DjlHfTokenizer exact = (DjlHfTokenizer) tokenizer;
        assertTrue(!exact.decode(ids).isEmpty(), "decode must round-trip non-empty text");
    }
}
