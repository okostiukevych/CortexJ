package io.cortexj.declarative;

/**
 * Decodes a model response into the annotated method's return type.
 * Implementations perform validation and must fail loudly on malformed output
 * (spec 48: prompt + validation, never silently accepted garbage).
 */
public interface PromptDecoder<T> {

    T decode(String response, Class<T> targetType);
}
