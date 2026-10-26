package io.cortexj.platform.java21;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Java21PlatformModuleTest {

    @Test
    void moduleIdentityAndVirtualThreadSupport() throws Exception {
        Java21PlatformModule module = new Java21PlatformModule();
        assertEquals("cortexj-platform-java21", module.name());
        assertEquals(21, module.featureVersion());
        assertTrue(module.supportsVirtualThreads());

        ExecutorService executor = module.createIoExecutor();
        try {
            Future<Boolean> isVirtual = executor.submit(() -> Thread.currentThread().isVirtual());
            assertTrue(isVirtual.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void executeNullAndExecuteAfterShutdownFailFastEvenWhenPermitsExhausted() throws Exception {
        BoundedVirtualThreadExecutor executor = new BoundedVirtualThreadExecutor(1);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.submit(() -> {
                inside.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(inside.await(5, TimeUnit.SECONDS));

            assertThrows(NullPointerException.class, () -> executor.execute(null));

            executor.shutdown();
            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {
            }));
        } finally {
            release.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void nestedSubmissionDoesNotDeadlockWhenWidthIsOne() throws Exception {
        BoundedVirtualThreadExecutor executor = new BoundedVirtualThreadExecutor(1);
        try {
            Future<String> outer = executor.submit(() -> {
                Future<String> inner = executor.submit(() -> "nested-ok");
                return inner.get(5, TimeUnit.SECONDS);
            });
            assertEquals("nested-ok", outer.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void topLevelConcurrencyIsBoundedByWidth() throws Exception {
        BoundedVirtualThreadExecutor executor = new BoundedVirtualThreadExecutor(2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(6);
        try {
            for (int i = 0; i < 6; i++) {
                executor.execute(() -> {
                    int now = active.incrementAndGet();
                    maxActive.accumulateAndGet(now, Math::max);
                    try {
                        Thread.sleep(30L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        active.decrementAndGet();
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertTrue(maxActive.get() <= 2, "expected maxActive <= 2 but got " + maxActive.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void configuredWidthHonorsPropertyAndRejectsInvalidValues() {
        String prev = System.getProperty(BoundedVirtualThreadExecutor.MAX_CONCURRENCY_PROPERTY);
        try {
            System.setProperty(BoundedVirtualThreadExecutor.MAX_CONCURRENCY_PROPERTY, "12");
            assertEquals(12, BoundedVirtualThreadExecutor.configuredWidth());
            System.setProperty(BoundedVirtualThreadExecutor.MAX_CONCURRENCY_PROPERTY, "-5");
            assertEquals(BoundedVirtualThreadExecutor.DEFAULT_MAX_CONCURRENCY,
                    BoundedVirtualThreadExecutor.configuredWidth());
            System.setProperty(BoundedVirtualThreadExecutor.MAX_CONCURRENCY_PROPERTY, "invalid");
            assertEquals(BoundedVirtualThreadExecutor.DEFAULT_MAX_CONCURRENCY,
                    BoundedVirtualThreadExecutor.configuredWidth());
        } finally {
            if (prev == null) {
                System.clearProperty(BoundedVirtualThreadExecutor.MAX_CONCURRENCY_PROPERTY);
            } else {
                System.setProperty(BoundedVirtualThreadExecutor.MAX_CONCURRENCY_PROPERTY, prev);
            }
        }
    }
}
