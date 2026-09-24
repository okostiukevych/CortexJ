package io.cortexj.inference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RerankingResponse {

    public final static class ScoredDocument {

        private final int index;
        private final double score;

        public ScoredDocument(int index, double score) {
            this.index = index;
            this.score = score;
        }

        public int index() {
            return index;
        }

        public double score() {
            return score;
        }
    }

    private final List<ScoredDocument> scores;

    public RerankingResponse(List<ScoredDocument> scores) {
        this.scores = Collections.unmodifiableList(new ArrayList<ScoredDocument>(scores));
    }

    public List<ScoredDocument> scores() {
        return scores;
    }
}
