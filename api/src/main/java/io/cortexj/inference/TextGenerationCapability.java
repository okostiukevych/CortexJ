package io.cortexj.inference;

import io.cortexj.stream.TokenStream;
import io.cortexj.stream.TokenStreams;

public interface TextGenerationCapability {

    GenerationResponse generate(GenerationRequest request);

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
