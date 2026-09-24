package io.cortexj.model;

public enum ArtifactFormat {
    GGUF(".gguf"),
    ONNX(".onnx"),
    SAFETENSORS(".safetensors"),
    TOKENIZER(".tokenizer.json"),
    PROCESSOR(".processor.json"),
    CONFIG(".json"),
    REMOTE(".remote"),
    RUNTIME_NATIVE(".native"),
    UNKNOWN("");

    private final String typicalExtension;

    ArtifactFormat(String typicalExtension) {
        this.typicalExtension = typicalExtension;
    }

    public String typicalExtension() {
        return typicalExtension;
    }

    public static ArtifactFormat fromFileName(String fileName) {
        if (fileName == null) {
            return UNKNOWN;
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".gguf")) {
            return GGUF;
        }
        if (lower.endsWith(".onnx")) {
            return ONNX;
        }
        if (lower.endsWith(".safetensors")) {
            return SAFETENSORS;
        }
        if (lower.endsWith("tokenizer.json") || lower.endsWith("tokenizer_config.json") || lower.endsWith(".model") || lower.endsWith(".vocab")) {
            return TOKENIZER;
        }
        if (lower.endsWith("preprocessor_config.json") || lower.endsWith("processor_config.json")) {
            return PROCESSOR;
        }
        if (lower.endsWith("config.json") || lower.endsWith("generation_config.json")) {
            return CONFIG;
        }
        return UNKNOWN;
    }

    public boolean isExecutableWeights() {
        return this == GGUF || this == ONNX || this == SAFETENSORS || this == REMOTE;
    }
}
