package io.cortexj.stream;

import io.cortexj.stream.internal.PushTokenStream;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit 2026-10-07 (#146): the live queue used to be unbounded after
 * subscribe - a fast producer with a stuck consumer grew it until OOM.
 */
class PushTokenStreamLiveOverflowTest {

    @Test
    void stuckConsumerTripsLiveCapWithTypedError() throws Exception {
        final PushTokenStream stream = new PushTokenStream();
        final List<String> received = Collections.synchronizedList(new ArrayList<String>());
        final AtomicReference<io.cortexj.error.CortexJException> failure =
                new AtomicReference<io.cortexj.error.CortexJException>();
        final AtomicBoolean first = new AtomicBoolean(false);
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        stream.subscribe(new TokenConsumer() {
            @Override
            public void onToken(Token token) {
                // block delivery exactly once, on the first token
                if (first.compareAndSet(false, true)) {
                    entered.countDown();
                    try {
                        if (!release.await(30, TimeUnit.SECONDS)) {
                            return;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                received.add(token.text());
            }

            @Override
            public void onComplete(StreamCompletion completion) {
            }

            @Override
            public void onError(io.cortexj.error.CortexJException error) {
                failure.set(error);
            }
        });

        // thread A: first emit enters delivery and blocks it there
        Thread firstEmit = new Thread(new Runnable() {
            @Override
            public void run() {
                stream.emit("t0", null);
            }
        });
        firstEmit.setDaemon(true);
        firstEmit.start();
        assertTrue(entered.await(10, TimeUnit.SECONDS), "delivery must start");

        // thread B: flood while delivery is stuck - pending must stop at the cap
        Thread flooder = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 1; i < PushTokenStream.LIVE_TOKEN_BUFFER_LIMIT + 100; i++) {
                    stream.emit("t" + i, null);
                }
            }
        });
        flooder.setDaemon(true);
        flooder.start();
        flooder.join(30_000L);
        release.countDown();
        firstEmit.join(30_000L);

        assertNotNull(failure.get(),
                "a stuck consumer must trip the live buffer cap with a typed error");
        assertEquals("CORTEXJ-INFER-9002", failure.get().code());
        assertTrue(received.size() <= PushTokenStream.LIVE_TOKEN_BUFFER_LIMIT + 1,
                "delivery must stay bounded, got " + received.size());
    }
}
