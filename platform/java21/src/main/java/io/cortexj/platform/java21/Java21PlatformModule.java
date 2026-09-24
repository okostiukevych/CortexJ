package io.cortexj.platform.java21;

import io.cortexj.spi.platform.PlatformModule;

public final class Java21PlatformModule implements PlatformModule {

    @Override
    public String name() {
        return "cortexj-platform-java21";
    }

    @Override
    public int featureVersion() {
        return 21;
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
