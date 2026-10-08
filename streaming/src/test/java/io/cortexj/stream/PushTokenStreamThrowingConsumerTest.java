package io.cortexj.stream;

import io.cortexj.stream.internal.PushTokenStream;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit 2026-10-08: a throwing consumer must fail the stream typed instead
 * of stalling it (drain-guard coverage).
 */
class PushTokenStreamThrowingConsumerTest {

    @Test
    void throwingOnTokenFailsStreamTyped() {
        PushTokenStream stream = new PushTokenStream();
        final AtomicReference<io.cortexj.error.CortexJException> failure =
                new AtomicReference<io.cortexj.error.CortexJException>();
        stream.subscribe(new TokenConsumer() {
            @Override
            public void onToken(Token token) {
                throw new IllegalStateException("consumer blew up");
            }

            @Override
            public void onComplete(StreamCompletion completion) {
            }

            @Override
            public void onError(io.cortexj.error.CortexJException error) {
                failure.set(error);
            }
        });
        stream.emit("hi", null);
        assertNotNull(failure.get(), "the throw must surface as a typed stream error");
        assertEquals("CORTEXJ-INFER-9002", failure.get().code());
        assertTrue(failure.get().getMessage().contains("IllegalStateException"),
                "the cause must stay visible, got: " + failure.get().getMessage());
        // further emits are terminally failed, never delivered, never stalling
        stream.emit("after", null);
    }

    @Test
    void throwingOnErrorDoesNotEscapeDrain() {
        PushTokenStream stream = new PushTokenStream();
        stream.subscribe(new TokenConsumer() {
            @Override
            public void onToken(Token token) {
            }

            @Override
            public void onComplete(StreamCompletion completion) {
            }

            @Override
            public void onError(io.cortexj.error.CortexJException error) {
                throw new IllegalStateException("onError blew up");
            }
        });
        // must not throw out of fail(), even though delivery raises
        stream.fail(new io.cortexj.error.InferenceException("CORTEXJ-INFER-9002", "boom"));
    }
}
