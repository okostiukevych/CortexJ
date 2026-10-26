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

    @Test
    void throwingOnCompleteDoesNotInvokeOnErrorAndOnCancelRunsAtMostOnce() {
        PushTokenStream stream = new PushTokenStream();
        final java.util.concurrent.atomic.AtomicInteger cancelCount =
                new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicBoolean errorCalled =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        stream.onCancel(new Runnable() {
            @Override
            public void run() {
                cancelCount.incrementAndGet();
            }
        });
        StreamSubscription sub = stream.subscribe(new TokenConsumer() {
            @Override
            public void onToken(Token token) {
            }

            @Override
            public void onComplete(StreamCompletion completion) {
                throw new IllegalStateException("onComplete blew up");
            }

            @Override
            public void onError(io.cortexj.error.CortexJException error) {
                errorCalled.set(true);
            }
        });
        stream.complete(io.cortexj.inference.FinishReason.STOP, new io.cortexj.inference.TokenUsage(1L, 1L));
        org.junit.jupiter.api.Assertions.assertFalse(errorCalled.get(),
                "a throw inside onComplete must not re-enter onError");

        sub.cancel();
        sub.cancel();
        stream.close();
        assertEquals(1, cancelCount.get(), "onCancel action must execute at most once");

        // Late onCancel registration on an already-closed stream runs immediately once
        PushTokenStream closedStream = new PushTokenStream();
        closedStream.close();
        final java.util.concurrent.atomic.AtomicInteger lateCancelCount =
                new java.util.concurrent.atomic.AtomicInteger();
        closedStream.onCancel(new Runnable() {
            @Override
            public void run() {
                lateCancelCount.incrementAndGet();
            }
        });
        assertEquals(1, lateCancelCount.get());
    }
}
