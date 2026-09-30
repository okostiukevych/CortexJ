package io.cortexj.stream;

import io.cortexj.error.CortexJException;
import io.cortexj.inference.FinishReason;
import io.cortexj.stream.internal.PushTokenStream;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalOrderingTest {

    private static void assertNoTokenAfterTerminal(List<String> events, int round) {
        int terminalIndex = events.indexOf("terminal");
        assertTrue(terminalIndex >= 0, "a terminal must be delivered (round " + round + ")");
        assertEquals(terminalIndex + 1, events.size(),
                "onToken must never be delivered after the terminal (round " + round + "): "
                        + events);
    }

    @Test
    void onTokenNeverAfterTerminalUnderConcurrentEmitAndComplete() throws Exception {
        for (int round = 0; round < 200; round++) {
            final PushTokenStream stream = new PushTokenStream();
            final List<String> events = Collections.synchronizedList(new ArrayList<String>());
            final CountDownLatch terminal = new CountDownLatch(1);
            stream.subscribe(new TokenConsumer() {
                @Override
                public void onToken(Token token) {
                    events.add("token");
                }

                @Override
                public void onComplete(StreamCompletion completion) {
                    events.add("terminal");
                    terminal.countDown();
                }

                @Override
                public void onError(CortexJException error) {
                    events.add("terminal");
                    terminal.countDown();
                }
            });
            Thread producer = new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int i = 0; i < 50; i++) {
                        stream.emit("t" + i, null);
                    }
                }
            });
            Thread finisher = new Thread(new Runnable() {
                @Override
                public void run() {
                    stream.complete(FinishReason.STOP, null);
                }
            });
            Thread failer = new Thread(new Runnable() {
                @Override
                public void run() {
                    stream.fail(new CortexJException("CORTEXJ-INFER-9002", "boom"));
                }
            });
            producer.start();
            finisher.start();
            failer.start();
            producer.join(5_000);
            finisher.join(5_000);
            failer.join(5_000);
            assertTrue(terminal.await(5, TimeUnit.SECONDS));
            assertNoTokenAfterTerminal(events, round);
        }
    }

    @Test
    void lateSubscriberReplayNeverDeliversTokenAfterTerminal() throws Exception {
        for (int round = 0; round < 200; round++) {
            final PushTokenStream stream = new PushTokenStream();
            final List<String> events = Collections.synchronizedList(new ArrayList<String>());
            Thread producer = new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int i = 0; i < 30; i++) {
                        stream.emit("t" + i, null);
                    }
                    stream.complete(FinishReason.STOP, null);
                }
            });
            producer.start();
            // subscribe while the producer is still emitting: the replay path
            // must preserve global order under the lock
            Thread subscriber = new Thread(new Runnable() {
                @Override
                public void run() {
                    stream.subscribe(new TokenConsumer() {
                        @Override
                        public void onToken(Token token) {
                            events.add("token");
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
            producer.join(5_000);
            subscriber.join(5_000);
            assertNoTokenAfterTerminal(events, round);
        }
    }
}
