package io.cortexj.stream;

import io.cortexj.error.CortexJException;
import io.cortexj.inference.FinishReason;

public final class TokenStreams {

    private TokenStreams() {
    }

    public static TokenStream single(final String text) {
        return new TokenStream() {
            @Override
            public StreamSubscription subscribe(TokenConsumer consumer) {
                consumer.onToken(new Token(text, 0, null));
                consumer.onComplete(new StreamCompletion(FinishReason.STOP, null));
                return new StreamSubscription() {
                    @Override
                    public void cancel() {
                    }
                };
            }

            @Override
            public void close() {
            }
        };
    }

    public static TokenStream failed(final CortexJException error) {
        return new TokenStream() {
            @Override
            public StreamSubscription subscribe(TokenConsumer consumer) {
                consumer.onError(error);
                return new StreamSubscription() {
                    @Override
                    public void cancel() {
                    }
                };
            }

            @Override
            public void close() {
            }
        };
    }
}
