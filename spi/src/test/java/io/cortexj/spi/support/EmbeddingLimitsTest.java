package io.cortexj.spi.support;

import io.cortexj.error.InferenceException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingLimitsTest {

    @Test
    void normalBatchesPass() {
        List<String> texts = new ArrayList<String>();
        texts.add("hello");
        texts.add("world");
        EmbeddingLimits.checkEmbedLimits(texts);
        EmbeddingLimits.checkEmbedLimits(Collections.<String>emptyList());
    }

    @Test
    void oversizedBatchIsRefusedTyped() {
        List<String> texts = new ArrayList<String>();
        for (int i = 0; i < 4097; i++) {
            texts.add("hi");
        }
        InferenceException failure = assertThrows(InferenceException.class,
                new org.junit.jupiter.api.function.Executable() {
                    @Override
                    public void execute() {
                        EmbeddingLimits.checkEmbedLimits(texts);
                    }
                });
        assertTrue(failure.getMessage().contains("exceeding the cap of 4096"),
                "refusal must name the cap, got: " + failure.getMessage());
        assertEquals("CORTEXJ-INFER-9001", failure.code());
    }

    @Test
    void oversizedTextIsRefusedTyped() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 1000001; i++) {
            huge.append('x');
        }
        final List<String> texts = Collections.singletonList(huge.toString());
        InferenceException failure = assertThrows(InferenceException.class,
                new org.junit.jupiter.api.function.Executable() {
                    @Override
                    public void execute() {
                        EmbeddingLimits.checkEmbedLimits(texts);
                    }
                });
        assertTrue(failure.getMessage().contains("exceeding the cap of 1000000"),
                "refusal must name the cap, got: " + failure.getMessage());
    }

    @Test
    void systemPropertyOverridesCap() {
        System.setProperty("cortexj.embed.maxTexts", "2");
        try {
            final List<String> texts = new ArrayList<String>();
            texts.add("a");
            texts.add("b");
            texts.add("c");
            assertThrows(InferenceException.class,
                    new org.junit.jupiter.api.function.Executable() {
                        @Override
                        public void execute() {
                            EmbeddingLimits.checkEmbedLimits(texts);
                        }
                    });
        } finally {
            System.clearProperty("cortexj.embed.maxTexts");
        }
    }

    @Test
    void invalidOverrideFallsBackToDefault() {
        System.setProperty("cortexj.embed.maxTexts", "not-a-number");
        try {
            assertEquals(4096, EmbeddingLimits.limit(
                    "cortexj.embed.maxTexts", "CORTEXJ_EMBED_MAX_TEXTS", 4096));
        } finally {
            System.clearProperty("cortexj.embed.maxTexts");
        }
    }
}
