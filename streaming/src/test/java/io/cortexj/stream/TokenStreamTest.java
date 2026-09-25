package io.cortexj.stream;

import io.cortexj.error.InferenceException;
import io.cortexj.inference.FinishReason;
import io.cortexj.stream.internal.TokenStreamPublisher;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenStreamTest {

    @Test
    void pushStreamDeliversTokensThenCompletion() {
        TokenStreamPublisher publisher = new TokenStreamPublisher();
        final List<String> tokens = new ArrayList<String>();
        final AtomicBoolean completed = new AtomicBoolean(false);

        publisher.stream().subscribe(new TokenConsumer() {
            @Override
            public void onToken(io.cortexj.stream.Token token) {
                tokens.add(token.text());
            }

            @Override
            public void onComplete(StreamCompletion completion) {
                completed.set(true);
            }

            @Override
            public void onError(io.cortexj.error.CortexJException error) {
            }
        });

        publisher.publish("Hel");
        publisher.publish("lo");
        publisher.complete(FinishReason.STOP, 3, 2);

        assertEquals(2, tokens.size());
        assertEquals("Hello", tokens.get(0) + tokens.get(1));
        assertTrue(completed.get());
    }

    @Test
    void singleSubscriptionEnforced() {
        TokenStreamPublisher publisher = new TokenStreamPublisher();
        TokenConsumer consumer = new TokenConsumer() {
            @Override
            public void onToken(Token token) {
            }

            @Override
            public void onComplete(StreamCompletion completion) {
            }

            @Override
            public void onError(io.cortexj.error.CortexJException error) {
            }
        };
        publisher.stream().subscribe(consumer);
        assertThrows(IllegalStateException.class, () -> publisher.stream().subscribe(consumer));
    }

    @Test
    void tokenStreamsSingleEmitsOneToken() {
        final AtomicInteger count = new AtomicInteger(0);
        final StringBuilder text = new StringBuilder();
        TokenStreams.single("hi").subscribe(new TokenConsumer() {
            @Override
            public void onToken(Token token) {
                count.incrementAndGet();
                text.append(token.text());
            }

            @Override
            public void onComplete(StreamCompletion completion) {
            }

            @Override
            public void onError(io.cortexj.error.CortexJException error) {
            }
        });
        assertEquals(1, count.get());
        assertEquals("hi", text.toString());
    }

    @Test
    void failedStreamReportsError() {
        final AtomicBoolean errored = new AtomicBoolean(false);
        TokenStreams.failed(new InferenceException("CORTEXJ-INFER-9001", "boom")).subscribe(new TokenConsumer() {
            @Override
            public void onToken(Token token) {
            }

            @Override
            public void onComplete(StreamCompletion completion) {
            }

            @Override
            public void onError(io.cortexj.error.CortexJException error) {
                errored.set(true);
            }
        });
        assertTrue(errored.get());
    }
}
