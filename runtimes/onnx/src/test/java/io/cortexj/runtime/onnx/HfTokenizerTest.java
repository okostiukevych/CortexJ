package io.cortexj.runtime.onnx;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HfTokenizerTest {

    @TempDir
    Path tempDir;

    private Path tokenizerJson(String content) throws Exception {
        Path file = tempDir.resolve("tokenizer.json");
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    void wordPieceEncodesWithRealVocabIds() throws Exception {
        String json = "{\"model\":{\"type\":\"WordPiece\",\"vocab\":{\"[CLS]\":0,\"[SEP]\":1,\"[UNK]\":2,\"hello\":3,\"##s\":4}}}";
        HfTokenizer.TokenEncoder encoder = HfTokenizer.load(tokenizerJson(json));
        int[] ids = encoder.encode("hellos");
        assertArrayEquals(new int[]{0, 3, 4, 1}, ids,
                "WordPiece must resolve ids from the real vocab, not zeros");
    }

    @Test
    void bpeEncodesWithMergesAndVocab() throws Exception {
        String json = "{\"model\":{\"type\":\"BPE\",\"vocab\":{\"l\":0,\"o\":1,\"lo\":2,\"w\":3,\"low\":4,\"<unk>\":5},"
                + "\"merges\":[\"l o\",\"lo w\"]}}";
        HfTokenizer.TokenEncoder encoder = HfTokenizer.load(tokenizerJson(json));
        int[] ids = encoder.encode("low");
        assertArrayEquals(new int[]{4}, ids);
    }

    @Test
    void fallbackEncodesDeterministically() {
        HfTokenizer.TokenEncoder encoder = HfTokenizer.fallback();
        int[] first = encoder.encode("hello world");
        int[] second = encoder.encode("hello world");
        assertArrayEquals(first, second);
        assertTrue(first.length == 2);
    }

    @Test
    void unknownTypeFallsBackGracefully() throws Exception {
        String json = "{\"model\":{\"type\":\"SomethingNew\"}}";
        HfTokenizer.TokenEncoder encoder = HfTokenizer.load(tokenizerJson(json));
        assertTrue(encoder.encode("text").length >= 1);
    }
}
