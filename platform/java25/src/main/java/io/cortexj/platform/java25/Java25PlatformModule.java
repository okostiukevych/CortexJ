package io.cortexj.platform.java25;

import io.cortexj.spi.platform.PlatformModule;

public final class Java25PlatformModule implements PlatformModule {

    @Override
    public String name() {
        return "cortexj-platform-java25";
    }

    @Override
    public int featureVersion() {
        return 25;
    }

    @Override
    public boolean supportsVirtualThreads() {
        return true;
    }

    @Override
    public java.util.concurrent.ExecutorService createIoExecutor() {
        return java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    }
}
