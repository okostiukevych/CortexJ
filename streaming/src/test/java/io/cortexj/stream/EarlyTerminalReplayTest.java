package io.cortexj.stream;

import io.cortexj.error.CortexJException;
import io.cortexj.inference.FinishReason;
import io.cortexj.stream.internal.PushTokenStream;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EarlyTerminalReplayTest {

    static final class RecordingConsumer implements TokenConsumer {

        final List<String> tokens = new ArrayList<String>();
        final AtomicInteger completions = new AtomicInteger();
        final AtomicInteger errors = new AtomicInteger();
        final AtomicReference<StreamCompletion> completion = new AtomicReference<StreamCompletion>();
        final AtomicReference<CortexJException> error = new AtomicReference<CortexJException>();
        final CountDownLatch terminal = new CountDownLatch(1);

        @Override
        public void onToken(Token token) {
            tokens.add(token.text());
        }

        @Override
        public void onComplete(StreamCompletion streamCompletion) {
            completions.incrementAndGet();
            completion.set(streamCompletion);
            terminal.countDown();
        }

        @Override
        public void onError(CortexJException failure) {
            errors.incrementAndGet();
            error.set(failure);
            terminal.countDown();
        }
    }

    @Test
    void completionFiredBeforeSubscribeIsReplayedExactlyOnce() throws Exception {
        PushTokenStream stream = new PushTokenStream();
        stream.complete(FinishReason.STOP, new io.cortexj.inference.TokenUsage(3L, 5L));
        stream.complete(FinishReason.LENGTH, null);

        RecordingConsumer consumer = new RecordingConsumer();
        stream.subscribe(consumer);

        assertTrue(consumer.terminal.await(1, TimeUnit.SECONDS),
                "a completion fired before subscribe() must be replayed, not lost");
        assertEquals(1, consumer.completions.get(), "exactly-once terminal semantics");
        assertEquals(FinishReason.STOP, consumer.completion.get().finishReason());
        assertEquals(0, consumer.errors.get());
    }

    @Test
    void failureFiredBeforeSubscribeIsReplayedExactlyOnce() throws Exception {
        PushTokenStream stream = new PushTokenStream();
        CortexJException boom = new CortexJException("CORTEXJ-INFER-9002", "connection refused", "retry");
        stream.fail(boom);
        stream.fail(boom);

        RecordingConsumer consumer = new RecordingConsumer();
        stream.subscribe(consumer);

        assertTrue(consumer.terminal.await(1, TimeUnit.SECONDS),
                "an error fired before subscribe() must be replayed, not lost");
        assertEquals(1, consumer.errors.get(), "exactly-once terminal semantics");
        assertEquals("CORTEXJ-INFER-9002", consumer.error.get().code());
        assertEquals(0, consumer.completions.get());
    }

    @Test
    void tokensEmittedBeforeSubscribeAreReplayedInOrderThenTerminal() throws Exception {
        PushTokenStream stream = new PushTokenStream();
        stream.emit("first", null);
        stream.emit("second", null);
        stream.emit("third", null);
        stream.complete(FinishReason.STOP, null);

        RecordingConsumer consumer = new RecordingConsumer();
        stream.subscribe(consumer);

        assertTrue(consumer.terminal.await(1, TimeUnit.SECONDS));
        assertEquals(java.util.Arrays.asList("first", "second", "third"), consumer.tokens,
                "early tokens must be replayed in emission order");
        assertEquals(1, consumer.completions.get());
    }

    @Test
    void tokensEmittedAfterTerminalAreDropped() throws Exception {
        PushTokenStream stream = new PushTokenStream();
        stream.complete(FinishReason.STOP, null);
        stream.emit("too-late", null);

        RecordingConsumer consumer = new RecordingConsumer();
        stream.subscribe(consumer);

        assertTrue(consumer.terminal.await(1, TimeUnit.SECONDS));
        assertTrue(consumer.tokens.isEmpty(), "no tokens may be delivered after the terminal signal");
    }

    @Test
    void unboundedProductionWithoutSubscriberFailsWithTypedException() throws Exception {
        PushTokenStream stream = new PushTokenStream();
        for (int i = 0; i < PushTokenStream.EARLY_TOKEN_BUFFER_LIMIT + 100; i++) {
            stream.emit("token-" + i, null);
        }

        RecordingConsumer consumer = new RecordingConsumer();
        stream.subscribe(consumer);

        assertTrue(consumer.terminal.await(1, TimeUnit.SECONDS),
                "overflow must produce a terminal signal");
        assertEquals(0, consumer.completions.get(),
                "overflow must not masquerade as a FinishReason.CONTENT_FILTER completion");
        assertEquals(1, consumer.errors.get(), "exactly one typed overflow error");
        assertTrue(consumer.error.get() instanceof io.cortexj.error.InferenceException,
                "overflow must be a typed InferenceException, got: " + consumer.error.get());
        assertEquals("CORTEXJ-INFER-9002", consumer.error.get().code(),
                "same typed code as TokenFlowAdapter's overflow policy");
        assertTrue(consumer.error.get().getMessage().contains("buffer overflow"),
                "message must identify the overflow: " + consumer.error.get().getMessage());
        assertEquals(PushTokenStream.EARLY_TOKEN_BUFFER_LIMIT, consumer.tokens.size(),
                "the early buffer must be capped at its limit");
    }
}
