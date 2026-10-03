package io.cortexj.spi.platform;

import java.util.concurrent.ExecutorService;

public interface PlatformModule {

    String name();

    int featureVersion();

    boolean supportsVirtualThreads();

    ExecutorService createIoExecutor();

    static PlatformModule forCurrentVmFallback() {
        return new PlatformModule() {
            @Override
            public String name() {
                return "fallback";
            }

            @Override
            public int featureVersion() {
                return 8;
            }

            @Override
            public boolean supportsVirtualThreads() {
                return false;
            }

            @Override
            public ExecutorService createIoExecutor() {
                int width = Math.max(8, Runtime.getRuntime().availableProcessors() * 2);
                return new java.util.concurrent.ThreadPoolExecutor(
                        width, width, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                        new java.util.concurrent.LinkedBlockingQueue<Runnable>(256),
                        daemonThreadFactory("cortexj-io"),
                        new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
            }
        };
    }

    static java.util.concurrent.ThreadFactory daemonThreadFactory(final String prefix) {
        return new java.util.concurrent.ThreadFactory() {
            private final java.util.concurrent.atomic.AtomicInteger counter =
                    new java.util.concurrent.atomic.AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
    }
}
