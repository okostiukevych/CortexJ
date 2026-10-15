package io.cortexj.train.data;

import io.cortexj.train.LmExample;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Batches LM examples: right-pad with id 0, labels are the next
 * token, mask carries the lossMask of the NEXT position (0 on pads
 * and the last position - nothing to predict). Deterministic shuffle
 * per seed.
 */
public final class LmBatcher {

    /** One padded training batch (all arrays are [batch][seq]). */
    public static final class LmBatch {
        public final int[][] ids;
        public final int[][] labels;
        public final float[][] mask;
        public final int sequenceLength;

        LmBatch(int[][] ids, int[][] labels, float[][] mask, int sequenceLength) {
            this.ids = ids;
            this.labels = labels;
            this.mask = mask;
            this.sequenceLength = sequenceLength;
        }
    }

    public static List<LmBatch> batch(List<LmExample> examples, int batchSize,
                                      int maxSeqLen, long seed, boolean shuffle) {
        List<LmExample> ordered = new ArrayList<LmExample>(examples);
        if (shuffle) {
            Collections.shuffle(ordered, new java.util.Random(seed));
        }
        List<LmBatch> batches = new ArrayList<LmBatch>();
        for (int start = 0; start < ordered.size(); start += batchSize) {
            List<LmExample> slice = ordered.subList(start,
                    Math.min(start + batchSize, ordered.size()));
            int length = 0;
            for (LmExample example : slice) {
                length = Math.max(length, Math.min(example.length(), maxSeqLen));
            }
            int size = slice.size();
            int[][] ids = new int[size][length];
            int[][] labels = new int[size][length];
            float[][] mask = new float[size][length];
            for (int i = 0; i < size; i++) {
                int[] tokens = slice.get(i).tokens();
                int[] lossMask = slice.get(i).lossMask();
                int exampleLength = Math.min(tokens.length, Math.min(length, maxSeqLen));
                for (int t = 0; t < length; t++) {
                    if (t < exampleLength) {
                        ids[i][t] = tokens[t];
                    }
                    if (t + 1 < exampleLength) {
                        labels[i][t] = tokens[t + 1];
                        mask[i][t] = lossMask[t + 1];
                    }
                }
            }
            batches.add(new LmBatch(ids, labels, mask, length));
        }
        return batches;
    }

    private LmBatcher() {
    }
}
