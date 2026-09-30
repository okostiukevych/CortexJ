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
