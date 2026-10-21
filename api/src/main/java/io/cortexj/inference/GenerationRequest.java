package io.cortexj.inference;

import io.cortexj.model.ChatMessage;
import io.cortexj.model.ToolDefinition;
import io.cortexj.planner.RuntimeOptions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class GenerationRequest {

    private final String prompt;
    private final List<ChatMessage> messages;
    private final Double temperature;
    private final Integer maxTokens;
    private final Double topP;
    private final Integer topK;
    private final Long seed;
    private final List<String> stopSequences;
    private final List<ToolDefinition> tools;
    private final RuntimeOptions runtimeOptions;

    private GenerationRequest(Builder builder) {
        this.prompt = builder.prompt;
        this.messages = Collections.unmodifiableList(new ArrayList<ChatMessage>(builder.messages));
        this.temperature = builder.temperature;
        this.maxTokens = builder.maxTokens;
        this.topP = builder.topP;
        this.topK = builder.topK;
        this.seed = builder.seed;
        this.stopSequences = Collections.unmodifiableList(new ArrayList<String>(builder.stopSequences));
        this.tools = Collections.unmodifiableList(new ArrayList<ToolDefinition>(builder.tools));
        this.runtimeOptions = builder.runtimeOptions;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static GenerationRequest prompt(String prompt) {
        return builder().prompt(prompt).build();
    }

    public String prompt() {
        return prompt == null ? "" : prompt;
    }

    public List<ChatMessage> messages() {
        return messages;
    }

    public Double temperature() {
        return temperature;
    }

    public Integer maxTokens() {
        return maxTokens;
    }

    public Double topP() {
        return topP;
    }

    public Integer topK() {
        return topK;
    }

    public Long seed() {
        return seed;
    }

    public List<String> stopSequences() {
        return stopSequences;
    }

    /** Tools the caller declares as available, empty when none were declared. */
    public List<ToolDefinition> tools() {
        return tools;
    }

    public RuntimeOptions runtimeOptions() {
        return runtimeOptions;
    }

    public boolean isChat() {
        return !messages.isEmpty();
    }

    public static final class Builder {

        private String prompt;
        private final List<ChatMessage> messages = new ArrayList<ChatMessage>();
        private Double temperature;
        private Integer maxTokens;
        private Double topP;
        private Integer topK;
        private Long seed;
        private final List<String> stopSequences = new ArrayList<String>();
        private final List<ToolDefinition> tools = new ArrayList<ToolDefinition>();
        private RuntimeOptions runtimeOptions;

        public Builder prompt(String prompt) {
            this.prompt = prompt;
            return this;
        }

        public Builder tool(ToolDefinition tool) {
            if (tool != null) {
                tools.add(tool);
            }
            return this;
        }

        public Builder tools(List<ToolDefinition> values) {
            if (values != null) {
                tools.addAll(values);
            }
            return this;
        }

        public Builder message(ChatMessage message) {
            if (message != null) {
                messages.add(message);
            }
            return this;
        }

        public Builder messages(ChatMessage... values) {
            messages.addAll(Arrays.asList(values));
            return this;
        }

        public Builder temperature(double value) {
            this.temperature = Double.valueOf(value);
            return this;
        }

        public Builder maxTokens(int value) {
            this.maxTokens = Integer.valueOf(value);
            return this;
        }

        public Builder topP(double value) {
            this.topP = Double.valueOf(value);
            return this;
        }

        public Builder topK(int value) {
            this.topK = Integer.valueOf(value);
            return this;
        }

        public Builder seed(long value) {
            this.seed = Long.valueOf(value);
            return this;
        }

        public Builder stopSequence(String sequence) {
            if (sequence != null && !sequence.isEmpty()) {
                stopSequences.add(sequence);
            }
            return this;
        }

        public Builder runtimeOptions(RuntimeOptions options) {
            this.runtimeOptions = options;
            return this;
        }

        public GenerationRequest build() {
            if ((prompt == null || prompt.isEmpty()) && messages.isEmpty()) {
                throw new IllegalArgumentException("Generation request requires a prompt or messages");
            }
            return new GenerationRequest(this);
        }
    }
}
