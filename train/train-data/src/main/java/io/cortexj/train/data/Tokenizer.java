package io.cortexj.train.data;

/**
 * Tokenizer SPI for the training path. The training implementation
 * must be HF-exact; the pure-Java HfTokenizer (different
 * pre-tokenizer regex) is forbidden here (no fake compatibility).
 */
public interface Tokenizer {

    int[] encode(String text);

    int vocabSize();
}
