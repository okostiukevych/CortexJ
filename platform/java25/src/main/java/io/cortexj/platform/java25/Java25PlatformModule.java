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
        // audit 2026-10-10: bounded admission instead of an unbounded
        // virtual-thread stampede (see BoundedVirtualThreadExecutor)
        return new BoundedVirtualThreadExecutor(BoundedVirtualThreadExecutor.configuredWidth());
    }
}
