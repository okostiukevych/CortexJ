package io.cortexj.tck;

import io.cortexj.spi.platform.PlatformModule;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract for {@link PlatformModule} implementations (audit 2026-10-07,
 * #148): LTS-specific executors previously had no behavioral contract.
 */
public abstract class PlatformModuleContract {

    protected abstract PlatformModule module();

    @Test
    public void identityIsPresent() {
        assertNotNull(module().name(), "module name must not be null");
        assertTrue(!module().name().trim().isEmpty(), "module name must not be blank");
        assertTrue(module().featureVersion() >= 8, "feature version must be a real Java version");
    }

    @Test
    public void ioExecutorRunsAndShutsDown() throws Exception {
        ExecutorService executor = module().createIoExecutor();
        assertNotNull(executor, "the IO executor must not be null");
        try {
            final AtomicBoolean ran = new AtomicBoolean(false);
            Future<?> task = executor.submit(new Runnable() {
                @Override
                public void run() {
                    ran.set(true);
                }
            });
            task.get(10, TimeUnit.SECONDS);
            assertTrue(ran.get(), "the IO executor must run submitted work");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void daemonThreadFactoryMarksDaemonThreads() throws Exception {
        java.util.concurrent.ThreadFactory factory =
                PlatformModule.daemonThreadFactory("tck-probe");
        final AtomicBoolean daemon = new AtomicBoolean(false);
        Thread probe = factory.newThread(new Runnable() {
            @Override
            public void run() {
                daemon.set(Thread.currentThread().isDaemon());
            }
        });
        probe.start();
        probe.join(10_000L);
        assertTrue(daemon.get(), "platform threads must be daemon so they never block JVM exit");
    }
}
