package io.cortexj.platform.java11;

import io.cortexj.spi.platform.PlatformModule;

public final class Java11PlatformModule implements PlatformModule {

    @Override
    public String name() {
        return "cortexj-platform-java11";
    }

    @Override
    public int featureVersion() {
        return 11;
    }

    @Override
    public boolean supportsVirtualThreads() {
        return false;
    }

    @Override
    public java.util.concurrent.ExecutorService createIoExecutor() {
        int width = Math.max(8, Runtime.getRuntime().availableProcessors() * 2);
        return new java.util.concurrent.ThreadPoolExecutor(
                width, width, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.LinkedBlockingQueue<Runnable>(256),
                PlatformModule.daemonThreadFactory("cortexj-io"),
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    }
}
