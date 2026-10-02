package io.cortexj.core;

import io.cortexj.CortexJ;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelReference;
import io.cortexj.observability.PlatformEvent;
import io.cortexj.observability.PlatformEventListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncEventDeliveryTest {

    @TempDir
    Path workDir;

    static final class SlowListener implements PlatformEventListener {

        private final CountDownLatch firstEvent = new CountDownLatch(1);

        @Override
        public void onEvent(PlatformEvent event) {
            firstEvent.countDown();
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    void slowListenerDoesNotStallInferenceInAsyncMode() throws Exception {
        SlowListener slow = new SlowListener();
        CortexJ platform = CortexJ.builder()
                .runtimeProvider(new ModelHandleCloseRaceTest.CountingRuntimeProvider())
                .repositoryProvider(new EndToEndLoadTest.FakeRepository())
                .eventListener(slow)
                .asyncEventDelivery(true)
                .workingDirectory(workDir)
                .build();
        try {
            assertTrue(slow.firstEvent.await(10, TimeUnit.SECONDS),
                    "the platform start event must reach the listener through the async queue");

            long started = System.nanoTime();
            ModelHandle handle = platform.models().load(ModelReference.of("e2e/test-model"));
            long loadMillis = (System.nanoTime() - started) / 1_000_000L;

            assertTrue(loadMillis < 2_000L,
                    "a slow listener must not stall model loads in async mode (load took "
                            + loadMillis + "ms)");
            handle.close();
        } finally {
            platform.close();
        }
    }

    @Test
    void eventsStillDeliveredInAsyncMode() throws Exception {
        final AtomicInteger total = new AtomicInteger();
        final CountDownLatch received = new CountDownLatch(3);
        CortexJ platform = CortexJ.builder()
                .runtimeProvider(new ModelHandleCloseRaceTest.CountingRuntimeProvider())
                .repositoryProvider(new EndToEndLoadTest.FakeRepository())
                .eventListener(new PlatformEventListener() {
                    @Override
                    public void onEvent(PlatformEvent event) {
                        total.incrementAndGet();
                        received.countDown();
                    }
                })
                .asyncEventDelivery(true)
                .workingDirectory(workDir)
                .build();
        try {
            platform.models().load(ModelReference.of("e2e/test-model"));
            assertTrue(received.await(10, TimeUnit.SECONDS),
                    "the async bus must deliver platform events (start, model resolution, loaded); total="
                            + total.get());
        } finally {
            platform.close();
        }
    }

    @Test
    void slowListenerBlocksSynchronouslyInDefaultMode() throws Exception {
        SlowListener slow = new SlowListener();
        CortexJ platform = CortexJ.builder()
                .runtimeProvider(new ModelHandleCloseRaceTest.CountingRuntimeProvider())
                .repositoryProvider(new EndToEndLoadTest.FakeRepository())
                .eventListener(slow)
                .workingDirectory(workDir)
                .build();
        try {
            assertTrue(slow.firstEvent.await(10, TimeUnit.SECONDS));
            long started = System.nanoTime();
            platform.models().load(ModelReference.of("e2e/test-model"));
            long loadMillis = (System.nanoTime() - started) / 1_000_000L;
            assertTrue(loadMillis >= 100,
                    "in the default sync mode the listener is on the load path (documented)");
        } finally {
            platform.close();
        }
    }

    private static Set<Thread> cortexjEventThreads() {
        Set<Thread> threads = new HashSet<Thread>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().startsWith("cortexj-events")) {
                threads.add(thread);
            }
        }
        return threads;
    }

    @Test
    void dispatcherThreadExitsAfterClose() throws Exception {
        Set<Thread> before = cortexjEventThreads();
        final CountDownLatch received = new CountDownLatch(1);
        CortexJ platform = CortexJ.builder()
                .runtimeProvider(new ModelHandleCloseRaceTest.CountingRuntimeProvider())
                .repositoryProvider(new EndToEndLoadTest.FakeRepository())
                .eventListener(new PlatformEventListener() {
                    @Override
                    public void onEvent(PlatformEvent event) {
                        received.countDown();
                    }
                })
                .asyncEventDelivery(true)
                .workingDirectory(workDir)
                .build();
        Set<Thread> afterBuild = cortexjEventThreads();
        Set<Thread> mine = new HashSet<Thread>(afterBuild);
        mine.removeAll(before);
        assertTrue(received.await(10, TimeUnit.SECONDS),
                "the dispatcher must be draining events for this platform");
        assertTrue(!mine.isEmpty(),
                "async delivery must start a cortexj-events dispatcher thread");

        platform.close();

        long deadline = System.currentTimeMillis() + 10_000L;
        while (!mine.isEmpty()) {
            mine.retainAll(cortexjEventThreads());
            if (mine.isEmpty()) {
                break;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("the cortexj-events dispatcher thread must exit after "
                        + "close() - every build/dispose cycle must not strand a thread, still alive: "
                        + mine);
            }
            Thread.sleep(25);
        }
        assertTrue(mine.isEmpty(), "the dispatcher thread must exit after close()");
    }
}
