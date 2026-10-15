package io.cortexj.train.data;

import io.cortexj.train.LmExample;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LmBatcherTest {

    @Test
    void batchesPadShiftAndMaskHandComputedCase() {
        // example A: tokens [1,2,3], mask [0,0,1]; example B: tokens [4,5], mask [1,1]
        List<LmExample> examples = Arrays.asList(
                LmExample.of(new int[]{1, 2, 3}, new int[]{0, 0, 1}),
                LmExample.of(new int[]{4, 5}, new int[]{1, 1}));
        List<LmBatcher.LmBatch> batches = LmBatcher.batch(examples, 2, 8, 1L, false);
        assertEquals(1, batches.size());
        LmBatcher.LmBatch batch = batches.get(0);
        assertEquals(3, batch.sequenceLength);
        // labels = next token; pad id 0; mask = lossMask of the NEXT position; last/pad = 0
        assertArrayEquals(new int[][]{{1, 2, 3}, {4, 5, 0}}, batch.ids);
        assertArrayEquals(new int[][]{{2, 3, 0}, {5, 0, 0}}, batch.labels);
        assertArrayEquals(new float[][]{{0f, 1f, 0f}, {1f, 0f, 0f}}, batch.mask);
    }

    @Test
    void shuffleIsDeterministicPerSeed() {
        List<LmExample> examples = new ArrayList<LmExample>();
        for (int i = 0; i < 8; i++) {
            examples.add(LmExample.of(new int[]{i, i + 1, i + 2}, new int[]{1, 1, 1}));
        }
        List<LmBatcher.LmBatch> first = LmBatcher.batch(examples, 2, 8, 7L, true);
        List<LmBatcher.LmBatch> second = LmBatcher.batch(examples, 2, 8, 7L, true);
        assertArrayEquals(first.get(0).ids[0], second.get(0).ids[0]);
    }

    @Test
    void batchSizeSplitsExamples() {
        List<LmExample> examples = Arrays.asList(
                LmExample.of(new int[]{1}, new int[]{1}),
                LmExample.of(new int[]{2}, new int[]{1}),
                LmExample.of(new int[]{3}, new int[]{1}));
        List<LmBatcher.LmBatch> batches = LmBatcher.batch(examples, 2, 8, 1L, false);
        assertEquals(2, batches.size());
        assertEquals(2, batches.get(0).ids.length);
        assertEquals(1, batches.get(1).ids.length);
    }
}
