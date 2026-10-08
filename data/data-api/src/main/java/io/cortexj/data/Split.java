package io.cortexj.data;

public final class Split<A, B> {

    private final A train;
    private final B test;

    public Split(A train, B test) {
        this.train = train;
        this.test = test;
    }

    public A train() {
        return train;
    }

    public B test() {
        return test;
    }
}
