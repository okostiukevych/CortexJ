package io.cortexj.train;

import java.util.List;

/**
 * Tokenized LM training data handed to a live backend. The
 * fingerprint is over tokens (built by the data module), carried into
 * provenance by the backend.
 */
public interface LmDataset {

    List<LmExample> examples();

    int vocabSize();

    String fingerprint();
}
