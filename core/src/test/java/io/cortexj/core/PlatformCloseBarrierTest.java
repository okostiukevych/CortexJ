package io.cortexj.core;

import io.cortexj.CortexJ;
import io.cortexj.error.ModelLoadException;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelLifecycleState;
import io.cortexj.model.ModelReference;
import io.cortexj.spi.runtime.ModelRuntime;
import io.cortexj.spi.runtime.RuntimeModel;
import io.cortexj.spi.runtime.RuntimeModelRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Tag;

@Tag("concurrency")
class PlatformCloseBarrierTest {

    @TempDir
    Path workDir;

    static final class GatedLoadRuntimeProvider extends ModelHandleCloseRaceTest.CountingRuntimeProvider {

        final CountDownLatch loadEntered = new CountDownLatch(1);
        final CountDownLatch releaseLoad = new CountDownLatch(1);
        final AtomicInteger loadStarts = new AtomicInteger();

        @Override
        public ModelRuntime open(final io.cortexj.spi.runtime.RuntimeOpenRequest request) {
            return new ModelRuntime() {
                @Override
                public io.cortexj.runtime.RuntimeId runtimeId() {
                    return GatedLoadRuntimeProvider.this.id();
                }

                @Override
                public RuntimeModel load(RuntimeModelRequest loadRequest) {
                    loadStarts.incrementAndGet();
                    loadEntered.countDown();
                    try {
                        releaseLoad.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return new ModelHandleCloseRaceTest.CountingRuntimeModel();
                }

                @Override
                public void close() {
                }
            };
        }
    }

    static final class SlowLoadRuntimeProvider extends ModelHandleCloseRaceTest.CountingRuntimeProvider {

        final CountDownLatch loadEntered = new CountDownLatch(1);
        final AtomicInteger runtimeCloseCount = new AtomicInteger();

        @Override
        public ModelRuntime open(final io.cortexj.spi.runtime.RuntimeOpenRequest request) {
            return new ModelRuntime() {
                @Override
                public io.cortexj.runtime.RuntimeId runtimeId() {
                    return SlowLoadRuntimeProvider.this.id();
                }

                @Override
                public RuntimeModel load(RuntimeModelRequest loadRequest) {
                    loadEntered.countDown();
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    ModelHandleCloseRaceTest.CountingRuntimeModel model =
                            new ModelHandleCloseRaceTest.CountingRuntimeModel();
                    models.add(model);
                    return model;
                }

                @Override
                public void close() {
                    runtimeCloseCount.incrementAndGet();
                }
            };
        }
    }

    @Test
    void closeIsNotStarvedByNewLoadAttemptsOncePending() throws Exception {
        GatedLoadRuntimeProvider provider = new GatedLoadRuntimeProvider();
        CortexJ platform = CortexJ.builder()
                .runtimeProvider(provider)
                .repositoryProvider(new EndToEndLoadTest.FakeRepository())
                .workingDirectory(workDir)
                .build();

        Thread loader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    platform.models().load(ModelReference.of("e2e/test-model"));
                } catch (RuntimeException ignored) {
                }
            }
        });
        loader.start();
        assertTrue(provider.loadEntered.await(10, TimeUnit.SECONDS),
                "loader thread must reach the runtime load phase");

        final CountDownLatch closeDone = new CountDownLatch(1);
        Thread closer = new Thread(new Runnable() {
            @Override
            public void run() {
                platform.close();
                closeDone.countDown();
            }
        });
        closer.start();
        Thread.sleep(200);

        int startsWhileClosePending = provider.loadStarts.get();
        final java.util.List<RuntimeException> attemptFailures =
                new CopyOnWriteArrayList<RuntimeException>();
        java.util.List<Thread> attempts = new java.util.ArrayList<Thread>();
        for (int i = 0; i < 4; i++) {
            Thread attempt = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        platform.models().load(ModelReference.of("e2e/test-model"));
                    } catch (RuntimeException failure) {
                        attemptFailures.add(failure);
                    }
                }
            });
            attempts.add(attempt);
            attempt.start();
        }
        Thread.sleep(300);
        assertEquals(startsWhileClosePending, provider.loadStarts.get(),
                "no new load work may start once close() is pending: the fair load lock must "
                        + "queue new readers behind the writer instead of starving closeAll()");

        provider.releaseLoad.countDown();
        assertTrue(closeDone.await(15, TimeUnit.SECONDS),
                "close() must complete once the in-flight load finishes - it must not be starved");
        for (Thread attempt : attempts) {
            attempt.join(15_000);
        }
        assertEquals(1, provider.loadStarts.get(),
                "only the original in-flight load may ever run work");
        assertTrue(!attemptFailures.isEmpty(),
                "the queued load attempts must fail once the platform is closed");
        for (RuntimeException failure : attemptFailures) {
            assertEquals(ModelLoadException.class, failure.getClass(),
                    "loads after close must fail with a typed error");
        }
    }

    @Test
    void closeWaitsForInFlightLoadAndClosesItsHandle() throws Exception {
        SlowLoadRuntimeProvider provider = new SlowLoadRuntimeProvider();
        CortexJ platform = CortexJ.builder()
                .runtimeProvider(provider)
                .repositoryProvider(new EndToEndLoadTest.FakeRepository())
                .workingDirectory(workDir)
                .build();

        final ModelHandle[] loaded = new ModelHandle[1];
        final RuntimeException[] loaderFailure = new RuntimeException[1];
        Thread loader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    loaded[0] = platform.models().load(ModelReference.of("e2e/test-model"));
                } catch (RuntimeException failure) {
                    loaderFailure[0] = failure;
                }
            }
        });
        loader.start();
        assertTrue(provider.loadEntered.await(10, TimeUnit.SECONDS),
                "loader thread must reach the runtime load phase");
        Thread.sleep(50);

        long closeStarted = System.nanoTime();
        platform.close();
        long closeWaitMillis = (System.nanoTime() - closeStarted) / 1_000_000L;

        loader.join(10_000);
        if (loaderFailure[0] != null) {
            throw loaderFailure[0];
        }
        assertTrue(closeWaitMillis >= 100,
                "close() must block until the in-flight load finishes (waited " + closeWaitMillis + "ms)");
        if (loaded[0] != null) {
            assertEquals(ModelLifecycleState.CLOSED, loaded[0].state(),
                    "the late-registered handle must be closed by closeAll()");
            assertEquals(1, provider.models.get(0).closeCount.get());
        }
        assertEquals(1, provider.runtimeCloseCount.get());

        assertEquals(ModelLoadException.class, assertThrows(RuntimeException.class,
                () -> platform.models().load(ModelReference.of("e2e/test-model"))).getClass(),
                "loads after close must fail with a typed error");
    }

    @Test
    void doublePlatformCloseIsIdempotent() {
        ModelHandleCloseRaceTest.CountingRuntimeProvider provider =
                new ModelHandleCloseRaceTest.CountingRuntimeProvider();
        CortexJ platform = CortexJ.builder()
                .runtimeProvider(provider)
                .repositoryProvider(new EndToEndLoadTest.FakeRepository())
                .workingDirectory(workDir)
                .build();
        ModelHandle handle = platform.models().load(ModelReference.of("e2e/test-model"));
        platform.close();
        platform.close();
        platform.close();
        assertEquals(ModelLifecycleState.CLOSED, handle.state());
        assertEquals(1, provider.models.get(0).closeCount.get(),
                "handle resources must be released exactly once even with repeated platform.close()");
    }
}
