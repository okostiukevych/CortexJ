package io.cortexj.core;

import io.cortexj.CortexJ;
import io.cortexj.inference.InferenceRequest;
import io.cortexj.inference.InferenceResponse;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelReference;
import io.cortexj.spi.platform.PlatformModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutorSeparationTest {

    @TempDir
    Path workDir;

    @Test
    void ioPoolRunsBlockingWorkInParallelAtFullWidth() throws Exception {
        int expectedWidth = Math.max(8, Runtime.getRuntime().availableProcessors() * 2);
        ExecutorService executor = PlatformModule.forCurrentVmFallback().createIoExecutor();
        try {
            final CyclicBarrier barrier = new CyclicBarrier(expectedWidth);
            final CountDownLatch allThrough = new CountDownLatch(expectedWidth);
            for (int i = 0; i < expectedWidth; i++) {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            barrier.await(10, TimeUnit.SECONDS);
                        } catch (Exception failure) {
                            // barrier timeout below fails the test
                        }
                        allThrough.countDown();
                    }
                });
            }
            assertTrue(allThrough.await(15, TimeUnit.SECONDS),
                    "all " + expectedWidth + " blocking tasks must run concurrently — "
                            + "the IO pool must be at least " + expectedWidth + " wide, not 2-4 threads");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void executeAsyncIsNotStarvedByBlockedIoExecutor() throws Exception {
        final CountDownLatch blockIo = new CountDownLatch(1);
        ExecutorService tinyIo = Executors.newFixedThreadPool(1,
                PlatformModule.daemonThreadFactory("test-tiny-io"));
        tinyIo.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    blockIo.await();
                } catch (InterruptedException restored) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        CortexJ platform = CortexJ.builder()
                .runtimeProvider(new ModelHandleCloseRaceTest.CountingRuntimeProvider())
                .repositoryProvider(new EndToEndLoadTest.FakeRepository())
                .executor(tinyIo)
                .workingDirectory(workDir)
                .build();
        try {
            ModelHandle handle = platform.models().load(ModelReference.of("e2e/test-model"));
            InferenceResponse response = ((DefaultModelHandle) handle)
                    .executeAsync(InferenceRequest.builder().input("probe").build())
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            assertEquals("ok: probe", response.singleOutput(),
                    "executeAsync must run on the separate async pool even when the IO pool is fully blocked");
            handle.close();
        } finally {
            platform.close();
            blockIo.countDown();
            tinyIo.shutdownNow();
        }
    }
}
