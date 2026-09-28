package io.cortexj.platform.java8;

import io.cortexj.spi.platform.PlatformModule;

public final class Java8PlatformModule implements PlatformModule {

    @Override
    public String name() {
        return "cortexj-platform-java8";
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
    public java.util.concurrent.ExecutorService createIoExecutor() {
        return java.util.concurrent.Executors.newFixedThreadPool(
                Math.max(8, Runtime.getRuntime().availableProcessors() * 2),
                PlatformModule.daemonThreadFactory("cortexj-io"));
    }
}
