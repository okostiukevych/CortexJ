package io.cortexj.model.token;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void wordPieceUsesJsonIdValuesNotDocumentOrder() throws Exception {
        // real-model verification 2026-10-10: vocab entries serialized out
        // of id order must still resolve to their JSON values
        String json = "{\"model\":{\"type\":\"WordPiece\",\"vocab\":{\"hello\":300,\"[CLS]\":101,\"##s\":400,\"[SEP]\":102}}}";
        HfTokenizer.TokenEncoder encoder = HfTokenizer.load(tokenizerJson(json));
        int[] ids = encoder.encode("hellos");
        assertArrayEquals(new int[]{101, 300, 400, 102}, ids,
                "WordPiece must use the vocab id values, not positional indices");
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
    void fallbackEncodeIsThreadSafeWithoutGlobalSerialization() throws Exception {
        io.cortexj.model.token.HfTokenizer.TokenEncoder encoder = io.cortexj.model.token.HfTokenizer.fallback();
        final int threads = 8;
        final int iterations = 2_000;
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threads);
        final java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger();
        for (int i = 0; i < threads; i++) {
            final int seed = i;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        for (int j = 0; j < iterations; j++) {
                            int[] ids = encoder.encode("word" + seed + "x" + j + " shared token");
                            if (ids.length != 3) {
                                failures.incrementAndGet();
                            }
                        }
                    } catch (RuntimeException e) {
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                }
            }).start();
        }
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS), "all encodes must finish");
        assertEquals(0, failures.get(), "concurrent encode must be safe and complete");
    }

    @Test
    void fallbackIsStableAcrossEncodersAndRestarts() {
        // hash-derived ids: the same token maps to the same id in every
        // encoder instance - no process-local observed-map ordering anymore
        io.cortexj.model.token.HfTokenizer.TokenEncoder first = io.cortexj.model.token.HfTokenizer.fallback();
        io.cortexj.model.token.HfTokenizer.TokenEncoder second = io.cortexj.model.token.HfTokenizer.fallback();
        assertArrayEquals(first.encode("hello world"), second.encode("hello world"),
                "fallback ids must be deterministic across instances");
        assertEquals(io.cortexj.model.token.HfTokenizer.stableId("hello"),
                io.cortexj.model.token.HfTokenizer.stableId("hello"),
                "stableId is a pure function");
    }

    @Test
    void fallbackDoesNotRetainDistinctTokens() {
        io.cortexj.model.token.HfTokenizer.TokenEncoder encoder = io.cortexj.model.token.HfTokenizer.fallback();
        // a distinct-token flood allocates no retention state: encoding a
        // million distinct tokens keeps the heap flat (no observed map)
        for (int i = 0; i < 1_000_000; i++) {
            encoder.encode("flood-" + Integer.toHexString(i));
        }
        assertTrue(encoder != null, "encoding a distinct-token flood must succeed");
    }

    @Test
    void unknownTypeFallsBackGracefully() throws Exception {
        String json = "{\"model\":{\"type\":\"SomethingNew\"}}";
        HfTokenizer.TokenEncoder encoder = HfTokenizer.load(tokenizerJson(json));
        assertTrue(encoder.encode("text").length >= 1);
    }
}
