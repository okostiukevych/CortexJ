package io.cortexj.platform.java17;

import io.cortexj.spi.platform.PlatformModule;

public final class Java17PlatformModule implements PlatformModule {

    @Override
    public String name() {
        return "cortexj-platform-java17";
    }

    @Override
    public int featureVersion() {
        return 17;
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
