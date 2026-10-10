package io.cortexj.declarative;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interface method as a declarative model call.
 *
 * <p>The template may reference method parameters by name using
 * {@code {paramName}} placeholders. The generated prompt is sent to the model's
 * text generation capability and the response is returned as {@code String}, or
 * decoded by a {@link PromptDecoder} for non-String return types.</p>
 *
 * <p>Honesty: declarative extraction is prompt + validation - it is NOT
 * guaranteed constrained decoding. Runtimes that support native schema constraints
 * should be used when guarantees are required.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ModelPrompt {

    /**
     * Prompt template, e.g. {@code "Extract the invoice number from: {document}"}.
     */
    String value();
}
