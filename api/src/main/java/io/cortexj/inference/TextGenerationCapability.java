package io.cortexj.inference;

import io.cortexj.stream.TokenStream;
import io.cortexj.stream.TokenStreams;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public interface TextGenerationCapability {

    GenerationResponse generate(GenerationRequest request);

    default CompletionStage<GenerationResponse> generateAsync(final GenerationRequest request) {
        return CompletableFuture.supplyAsync(new java.util.function.Supplier<GenerationResponse>() {
            @Override
            public GenerationResponse get() {
                return generate(request);
            }
        });
    }

    TokenStream stream(GenerationRequest request);

    static TextGenerationCapability nonStreaming(final TextGenerationCapability delegate) {
        return new TextGenerationCapability() {
            @Override
            public GenerationResponse generate(GenerationRequest request) {
                return delegate.generate(request);
            }

            @Override
            public TokenStream stream(GenerationRequest request) {
                GenerationResponse response = delegate.generate(request);
                return TokenStreams.single(response.text());
            }
        };
    }
}
