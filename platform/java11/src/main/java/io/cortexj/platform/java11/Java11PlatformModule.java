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
        return java.util.concurrent.Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors()));
    }
}
