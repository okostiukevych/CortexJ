package io.cortexj.stream.internal;

import io.cortexj.error.CortexJException;
import io.cortexj.inference.FinishReason;
import io.cortexj.stream.TokenStream;

public final class TokenStreamPublisher {

    private final PushTokenStream stream = new PushTokenStream();

    public void publish(String text) {
        stream.emit(text, null);
    }

    public void complete(FinishReason reason, long inputTokens, long outputTokens) {
        stream.complete(reason, new io.cortexj.inference.TokenUsage(inputTokens, outputTokens));
    }

    public void fail(CortexJException error) {
        stream.fail(error);
    }

    public void onCancel(Runnable action) {
        stream.onCancel(action);
    }

    public TokenStream stream() {
        return stream;
    }
}
