package io.cortexj.train;

/** One tokenized training example: ids plus a parallel 0/1 loss mask. */
public final class LmExample {

    private final int[] tokens;
    private final int[] lossMask;

    private LmExample(int[] tokens, int[] lossMask) {
        this.tokens = tokens.clone();
        this.lossMask = lossMask.clone();
    }

    public static LmExample of(int[] tokens, int[] lossMask) {
        if (tokens == null || lossMask == null || tokens.length != lossMask.length
                || tokens.length == 0) {
            throw new IllegalArgumentException("tokens and lossMask must be parallel and non-empty");
        }
        for (int mask : lossMask) {
            if (mask != 0 && mask != 1) {
                throw new IllegalArgumentException("loss mask entries must be 0 or 1");
            }
        }
        return new LmExample(tokens, lossMask);
    }

    public int[] tokens() {
        return tokens.clone();
    }

    public int[] lossMask() {
        return lossMask.clone();
    }

    public int length() {
        return tokens.length;
    }
}
