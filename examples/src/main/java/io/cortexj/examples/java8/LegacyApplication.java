package io.cortexj.examples.java8;

import io.cortexj.CortexJ;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.GenerationResponse;
import io.cortexj.inference.TextGenerationCapability;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelReference;

/**
 * Java 8 legacy application.
 *
 * <p>Possible execution (chosen by the planner, not the caller): Java 8 → ONNX Runtime
 * provider → CPU; or Java 8 → llama.cpp via the managed llama-server bridge. JInfer is
 * not required.</p>
 */
public final class LegacyApplication {

    public static void main(String[] args) {
        try (CortexJ platform = CortexJ.builder().build()) {
            try (ModelHandle model = platform.models()
                    .load(ModelReference.of("company/document-model"))) {

                // domain capability API (primary)
                TextGenerationCapability generator = model
                        .capability(TextGenerationCapability.class);
                GenerationResponse response = generator.generate(GenerationRequest.builder()
                        .prompt("Extract the important fields")
                        .build());
                System.out.println(response.text());

                // convenience shortcut — same capability under the hood
                GenerationResponse shortcut = model.generate(
                        GenerationRequest.prompt("Summarize in one sentence"));
                System.out.println(shortcut.text());
            }
        }
    }

    private LegacyApplication() {
    }
}
