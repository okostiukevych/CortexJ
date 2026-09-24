package io.cortexj.stream;

import io.cortexj.error.CortexJException;

public interface TokenConsumer {

    void onToken(Token token);

    void onComplete(StreamCompletion completion);

    void onError(CortexJException error);
}
