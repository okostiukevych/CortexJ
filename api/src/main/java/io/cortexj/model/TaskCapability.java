package io.cortexj.model;

import java.util.Set;

public enum TaskCapability {
    TEXT_GENERATION("text-generation"),
    EMBEDDING("embeddings"),
    RERANKING("reranking"),
    CLASSIFICATION("classification"),
    VISION("vision"),
    SPEECH_TO_TEXT("speech-to-text"),
    TEXT_TO_SPEECH("text-to-speech"),
    MULTIMODAL("multimodal");

    private final String commonTag;

    TaskCapability(String commonTag) {
        this.commonTag = commonTag;
    }

    public String commonTag() {
        return commonTag;
    }

    public static TaskCapability fromCommonTag(String tag) {
        if (tag == null) {
            return null;
        }
        String normalized = tag.trim().toLowerCase();
        if (normalized.equals("feature-extraction") || normalized.equals("sentence-similarity")) {
            return EMBEDDING;
        }
        if (normalized.equals("text2text-generation") || normalized.equals("conversational")) {
            return TEXT_GENERATION;
        }
        for (TaskCapability capability : values()) {
            if (capability.commonTag.equals(normalized)) {
                return capability;
            }
        }
        return null;
    }

    public static Set<TaskCapability> parseCommonTags(Iterable<String> tags) {
        Set<TaskCapability> result = new java.util.LinkedHashSet<TaskCapability>();
        for (String tag : tags) {
            TaskCapability capability = fromCommonTag(tag);
            if (capability != null) {
                result.add(capability);
            }
        }
        return result;
    }
}
