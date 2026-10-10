package io.cortexj.train;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The intent of one training run: model, dataset, strategy and budget.
 * Backend-specific details stay out of this request - backends are
 * selected and validated through the SPI, never hard-coded here.
 */
public final class TrainingRequest {

    private final String model;
    private final String dataset;
    private final TrainingStrategy strategy;
    private final int epochs;
    private final double learningRate;
    private final Device device;
    private final Map<String, String> parameters;
    private final TrainingData data;

    private TrainingRequest(Builder builder) {
        this.model = builder.model;
        this.dataset = builder.dataset;
        this.strategy = builder.strategy;
        this.epochs = builder.epochs;
        this.learningRate = builder.learningRate;
        this.device = builder.device;
        this.parameters = new LinkedHashMap<String, String>(builder.parameters);
        this.data = builder.data;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String model() {
        return model;
    }

    public String dataset() {
        return dataset;
    }

    public TrainingStrategy strategy() {
        return strategy;
    }

    public int epochs() {
        return epochs;
    }

    public double learningRate() {
        return learningRate;
    }

    public Device device() {
        return device;
    }

    public Map<String, String> parameters() {
        // audit 2026-10-10 (SpotBugs EI_EXPOSE_REP): return an owned copy -
        // the stored map must not be mutable through the getter
        return new LinkedHashMap<String, String>(parameters);
    }

    /**
     * The live training data hand-off (null for the planning-only path).
     */
    public TrainingData data() {
        return data;
    }

    public static final class Builder {

        private String model;
        private String dataset;
        private TrainingStrategy strategy = TrainingStrategy.FULL;
        private int epochs = 1;
        private double learningRate = 1e-4;
        private Device device = Device.AUTO;
        private final Map<String, String> parameters = new LinkedHashMap<String, String>();
        private TrainingData data;

        public Builder model(String model) {
            if (model == null || model.trim().isEmpty()) {
                throw new IllegalArgumentException("model must not be empty");
            }
            this.model = model.trim();
            return this;
        }

        public Builder dataset(String dataset) {
            if (dataset == null || dataset.trim().isEmpty()) {
                throw new IllegalArgumentException("dataset must not be empty");
            }
            this.dataset = dataset.trim();
            return this;
        }

        public Builder strategy(TrainingStrategy strategy) {
            if (strategy == null) {
                throw new IllegalArgumentException("strategy must not be null");
            }
            this.strategy = strategy;
            return this;
        }

        public Builder epochs(int epochs) {
            if (epochs < 1) {
                throw new IllegalArgumentException("epochs must be at least 1");
            }
            this.epochs = epochs;
            return this;
        }

        public Builder learningRate(double learningRate) {
            if (learningRate <= 0 || learningRate > 1) {
                throw new IllegalArgumentException("learning rate must sit in (0, 1]");
            }
            this.learningRate = learningRate;
            return this;
        }

        public Builder device(Device device) {
            if (device == null) {
                throw new IllegalArgumentException("device must not be null");
            }
            this.device = device;
            return this;
        }

        public Builder parameter(String key, String value) {
            if (key != null && value != null) {
                parameters.put(key, value);
            }
            return this;
        }

        public Builder data(TrainingData data) {
            this.data = data;
            return this;
        }

        public TrainingRequest build() {
            if (model == null) {
                throw new IllegalArgumentException("model is required");
            }
            if (dataset == null) {
                throw new IllegalArgumentException("dataset is required");
            }
            return new TrainingRequest(this);
        }
    }

    static List<String> validateIntent(TrainingRequest request) {
        List<String> problems = new ArrayList<String>();
        if (request == null) {
            problems.add("request is null");
        } else if (request.model() == null || request.dataset() == null) {
            problems.add("model and dataset are required");
        }
        return problems;
    }
}
