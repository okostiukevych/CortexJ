package io.cortexj.core;

import io.cortexj.CortexJ;
import io.cortexj.spi.platform.PlatformModule;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DaemonThreadPolicyTest {

    @Test
    void fallbackExecutorThreadsAreNamedDaemons() throws Exception {
        ExecutorService executor = PlatformModule.forCurrentVmFallback().createIoExecutor();
        try {
            final CountDownLatch done = new CountDownLatch(1);
            final boolean[] daemon = new boolean[1];
            final String[] name = new String[1];
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    Thread current = Thread.currentThread();
                    daemon[0] = current.isDaemon();
                    name[0] = current.getName();
                    done.countDown();
                }
            });
            assertTrue(done.await(10, TimeUnit.SECONDS), "task did not run");
            assertTrue(daemon[0], "platform pool threads must be daemons");
            assertTrue(name[0].startsWith("cortexj-io"),
                    "platform pool threads must be namespaced, got: " + name[0]);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void platformExecutorThreadsAreDaemons() throws Exception {
        CortexJ platform = CortexJ.builder()
                .runtimeProvider(new ModelHandleCloseRaceTest.CountingRuntimeProvider())
                .repositoryProvider(new EndToEndLoadTest.FakeRepository())
                .build();
        try {
            ExecutorService executor = ((DefaultCortexJ) platform).executor();
            final CountDownLatch done = new CountDownLatch(1);
            final boolean[] daemon = new boolean[1];
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    daemon[0] = Thread.currentThread().isDaemon();
                    done.countDown();
                }
            });
            assertTrue(done.await(10, TimeUnit.SECONDS), "task did not run");
            assertTrue(daemon[0],
                    "platform executor threads must never keep the JVM alive after main exits");
        } finally {
            platform.close();
        }
    }
}
