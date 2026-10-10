package io.cortexj.train.standard;

import io.cortexj.train.Optimizer;

/**
 * AdamW with decoupled weight decay. The builder mirrors the
 * high-level training example; {@link Standard#adamW(double)} stays
 * for the one-line form.
 */
public final class AdamW {

    private final double learningRate;
    private final double beta1;
    private final double beta2;
    private final double epsilon;
    private final double weightDecay;

    private AdamW(Builder builder) {
        this.learningRate = builder.learningRate;
        this.beta1 = builder.beta1;
        this.beta2 = builder.beta2;
        this.epsilon = builder.epsilon;
        this.weightDecay = builder.weightDecay;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optimizer build() {
        return Standard.adamW(learningRate, beta1, beta2, epsilon, weightDecay);
    }

    public static final class Builder {

        private double learningRate = -1;
        private double beta1 = 0.9;
        private double beta2 = 0.999;
        private double epsilon = 1e-8;
        private double weightDecay = 0.01;

        public Builder learningRate(double learningRate) {
            this.learningRate = learningRate;
            return this;
        }

        public Builder beta1(double beta1) {
            this.beta1 = beta1;
            return this;
        }

        public Builder beta2(double beta2) {
            this.beta2 = beta2;
            return this;
        }

        public Builder epsilon(double epsilon) {
            this.epsilon = epsilon;
            return this;
        }

        public Builder weightDecay(double weightDecay) {
            this.weightDecay = weightDecay;
            return this;
        }

        public Optimizer build() {
            if (learningRate <= 0 || learningRate > 1) {
                throw new IllegalArgumentException("learning rate must sit in (0, 1]");
            }
            return new AdamW(this).build();
        }
    }
}
