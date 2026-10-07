package io.cortexj.core;

import io.cortexj.CortexJ;
import io.cortexj.error.ModelLoadException;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelLifecycleState;
import io.cortexj.model.ModelReference;
import io.cortexj.spi.runtime.ModelRuntime;
import io.cortexj.spi.runtime.RuntimeModel;
import io.cortexj.spi.runtime.RuntimeModelRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("concurrency")
class LoadAsyncCloseBarrierTest {

    @TempDir
    Path workDir;

    @AfterEach
    void clearDownloadTuning() {
        System.clearProperty("cortexj.download.width");
        System.clearProperty("cortexj.download.queue");
    }

    static final class GatedLoadRuntimeProvider extends ModelHandleCloseRaceTest.CountingRuntimeProvider {

        final CountDownLatch loadEntered = new CountDownLatch(1);
        final CountDownLatch releaseLoad = new CountDownLatch(1);

        @Override
        public ModelRuntime open(final io.cortexj.spi.runtime.RuntimeOpenRequest request) {
            return new ModelRuntime() {
                @Override
                public io.cortexj.runtime.RuntimeId runtimeId() {
                    return GatedLoadRuntimeProvider.this.id();
                }

                @Override
                public RuntimeModel load(RuntimeModelRequest loadRequest) {
                    loadEntered.countDown();
                    try {
                        releaseLoad.await(30, TimeUnit.SECONDS);
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
    void closeWaitsForInFlightLoadAsyncAndClosesItsHandle() throws Exception {
        SlowLoadRuntimeProvider provider = new SlowLoadRuntimeProvider();
        CortexJ platform = CortexJ.builder()
                .runtimeProvider(provider)
                .repositoryProvider(new EndToEndLoadTest.FakeRepository())
                .workingDirectory(workDir)
                .build();

        java.util.concurrent.Future<ModelHandle> future =
                platform.models().loadAsync(ModelReference.of("e2e/test-model"));
        assertTrue(provider.loadEntered.await(10, TimeUnit.SECONDS),
                "the async load must reach the runtime load phase");
        Thread.sleep(50);

        final CountDownLatch closeDone = new CountDownLatch(1);
        Thread closer = new Thread(new Runnable() {
            @Override
            public void run() {
                platform.close();
                closeDone.countDown();
            }
        });
        closer.start();

        ModelHandle handle = future.get(15, TimeUnit.SECONDS);
        assertTrue(closeDone.await(15, TimeUnit.SECONDS),
                "close() must block until the in-flight async load finishes");
        closer.join(15_000);

        assertEquals(ModelLifecycleState.CLOSED, handle.state(),
                "the handle registered by the async task after the close snapshot must be "
                        + "closed by closeAll() - never orphaned (audit #89)");
        assertEquals(1, provider.models.get(0).closeCount.get(),
                "leak assertion: the async-loaded model must be closed exactly once");
        assertEquals(1, provider.runtimeCloseCount.get(),
                "leak assertion: the runtime must be closed exactly once");
    }

    @Test
    void closeFailsQueuedLoadAsyncFutureInsteadOfLeavingItHanging() throws Exception {
        System.setProperty("cortexj.download.width", "1");
        System.setProperty("cortexj.download.queue", "64");
        GatedLoadRuntimeProvider provider = new GatedLoadRuntimeProvider();
        CortexJ platform = CortexJ.builder()
                .runtimeProvider(provider)
                .repositoryProvider(new EndToEndLoadTest.FakeRepository())
                .workingDirectory(workDir)
                .build();

        java.util.concurrent.Future<ModelHandle> first =
                platform.models().loadAsync(ModelReference.of("e2e/test-model"));
        assertTrue(provider.loadEntered.await(10, TimeUnit.SECONDS),
                "the first async load must occupy the single download worker");

        java.util.concurrent.Future<ModelHandle> queued =
                platform.models().loadAsync(ModelReference.of("e2e/test-model"));

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

        provider.releaseLoad.countDown();
        first.get(15, TimeUnit.SECONDS);
        assertTrue(closeDone.await(15, TimeUnit.SECONDS),
                "close() must finish once the gated load completes");

        try {
            ModelHandle late = queued.get(15, TimeUnit.SECONDS);
            assertNotNull(late,
                    "if the queued task slipped in before close, it must still return a handle");
            assertEquals(ModelLifecycleState.CLOSED, late.state(),
                    "a handle handed out across close must arrive CLOSED, never orphaned");
        } catch (ExecutionException completedByBarrier) {
            Throwable cause = completedByBarrier.getCause();
            assertTrue(cause instanceof ModelLoadException,
                    "the queued loadAsync future must fail with a typed ModelLoadException, got: "
                            + cause);
        }
        closer.join(15_000);
    }
}
