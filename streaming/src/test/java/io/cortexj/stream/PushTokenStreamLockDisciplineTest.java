package io.cortexj.stream;

import io.cortexj.ErrorCode;
import io.cortexj.error.CortexJException;
import io.cortexj.error.InferenceException;
import io.cortexj.inference.FinishReason;
import io.cortexj.stream.internal.PushTokenStream;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lock discipline regression tests (GitHub #57): user callbacks must never run
 * under the stream monitor, so a blocking consumer chain (socket writes in the
 * server SSE handler) cannot pin a virtual-thread carrier and stall other
 * producer/subscriber threads. Ordering guarantees are preserved: terminal
 * exactly-once, no emit-after-complete, replay-before-live.
 */
class PushTokenStreamLockDisciplineTest {

    @Test
    void blockingConsumerDoesNotHoldTheMonitorForOtherThreads() throws Exception {
        final PushTokenStream stream = new PushTokenStream();
        final List<String> events = Collections.synchronizedList(new ArrayList<String>());
        final CountDownLatch consumerEntered = new CountDownLatch(1);
        final CountDownLatch releaseConsumer = new CountDownLatch(1);

        stream.subscribe(new TokenConsumer() {
            @Override
            public void onToken(Token token) {
                events.add(token.text());
                if ("first".equals(token.text())) {
                    consumerEntered.countDown();
                    try {
                        releaseConsumer.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            @Override
            public void onComplete(StreamCompletion completion) {
                events.add("terminal");
            }

            @Override
            public void onError(CortexJException error) {
                events.add("terminal");
            }
        });

        // the producer thread's emit dispatches into the blocking consumer
        Thread producer = new Thread(new Runnable() {
            @Override
            public void run() {
                stream.emit("first", null);
            }
        });
        producer.start();
        assertTrue(consumerEntered.await(5, TimeUnit.SECONDS),
                "the consumer must receive the first token");

        // while the consumer callback is blocked in flight, a concurrent emit
        // must proceed instead of blocking on the stream monitor (GitHub #57)
        final CountDownLatch secondEmitted = new CountDownLatch(1);
        Thread concurrentEmitter = new Thread(new Runnable() {
            @Override
            public void run() {
                stream.emit("second", null);
                secondEmitted.countDown();
            }
        });
        concurrentEmitter.start();
        assertTrue(secondEmitted.await(5, TimeUnit.SECONDS),
                "emit must not block on the monitor while a consumer callback is in flight");

        // a concurrent complete must proceed too
        final CountDownLatch completeReturned = new CountDownLatch(1);
        Thread concurrentCompleter = new Thread(new Runnable() {
            @Override
            public void run() {
                stream.complete(FinishReason.STOP, null);
                completeReturned.countDown();
            }
        });
        concurrentCompleter.start();
        assertTrue(completeReturned.await(5, TimeUnit.SECONDS),
                "complete must not block on the monitor while a consumer callback is in flight");

        releaseConsumer.countDown();
        producer.join(5_000);
        concurrentEmitter.join(5_000);
        concurrentCompleter.join(5_000);

        assertEquals(Arrays.asList("first", "second", "terminal"), events,
                "ordering preserved: the concurrently emitted token is delivered "
                        + "after the in-flight one and before the terminal");
    }

    @Test
    void replayOrderingPreservedWhenConsumerBlocksDuringReplay() throws Exception {
        final PushTokenStream stream = new PushTokenStream();
        stream.emit("a", null);
        stream.emit("b", null);
        stream.emit("c", null);

        final List<String> events = Collections.synchronizedList(new ArrayList<String>());
        final CountDownLatch replayBlocked = new CountDownLatch(1);
        final CountDownLatch releaseReplay = new CountDownLatch(1);

        Thread subscriber = new Thread(new Runnable() {
            @Override
            public void run() {
                stream.subscribe(new TokenConsumer() {
                    @Override
                    public void onToken(Token token) {
                        events.add(token.text());
                        if ("a".equals(token.text())) {
                            replayBlocked.countDown();
                            try {
                                releaseReplay.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }

                    @Override
                    public void onComplete(StreamCompletion completion) {
                        events.add("terminal");
                    }

                    @Override
                    public void onError(CortexJException error) {
                        events.add("terminal");
                    }
                });
            }
        });
        subscriber.start();
        assertTrue(replayBlocked.await(5, TimeUnit.SECONDS),
                "replay must reach the blocking consumer");

        // a concurrent emit while the replay is in flight must proceed and must
        // be delivered after the replayed tokens (replay-before-live)
        final CountDownLatch lateEmitted = new CountDownLatch(1);
        Thread concurrentEmitter = new Thread(new Runnable() {
            @Override
            public void run() {
                stream.emit("d", null);
                lateEmitted.countDown();
            }
        });
        concurrentEmitter.start();
        assertTrue(lateEmitted.await(5, TimeUnit.SECONDS),
                "emit must not block on the monitor while replay delivers under the drain guard");

        releaseReplay.countDown();
        subscriber.join(5_000);
        concurrentEmitter.join(5_000);

        assertEquals(Arrays.asList("a", "b", "c", "d"), events,
                "replay-before-live ordering preserved without holding the monitor");
    }

    @Test
    void earlyBufferOverflowFailsWithTypedExceptionNotContentFilter() throws Exception {
        PushTokenStream stream = new PushTokenStream();
        for (int i = 0; i < PushTokenStream.EARLY_TOKEN_BUFFER_LIMIT + 10; i++) {
            stream.emit("token-" + i, null);
        }

        final List<String> tokens = new ArrayList<String>();
        final AtomicInteger completions = new AtomicInteger();
        final AtomicInteger errors = new AtomicInteger();
        final AtomicReference<CortexJException> error = new AtomicReference<CortexJException>();
        final CountDownLatch terminal = new CountDownLatch(1);

        stream.subscribe(new TokenConsumer() {
            @Override
            public void onToken(Token token) {
                tokens.add(token.text());
            }

            @Override
            public void onComplete(StreamCompletion completion) {
                completions.incrementAndGet();
                terminal.countDown();
            }

            @Override
            public void onError(CortexJException failure) {
                errors.incrementAndGet();
                error.set(failure);
                terminal.countDown();
            }
        });

        assertTrue(terminal.await(5, TimeUnit.SECONDS), "overflow must produce a terminal signal");
        assertEquals(0, completions.get(),
                "overflow must not masquerade as a FinishReason.CONTENT_FILTER completion");
        assertEquals(1, errors.get(), "exactly one typed overflow error");
        assertTrue(error.get() instanceof InferenceException,
                "overflow must be a typed InferenceException, got: " + error.get());
        assertEquals(ErrorCode.INFERENCE_STREAM_FAILED, error.get().code(),
                "same typed code as TokenFlowAdapter's overflow policy");
        assertTrue(error.get().getMessage().contains("buffer overflow"),
                "message must identify the overflow: " + error.get().getMessage());
        assertEquals(PushTokenStream.EARLY_TOKEN_BUFFER_LIMIT, tokens.size(),
                "exactly the early-buffer capacity is replayed before the typed error");
    }
}
