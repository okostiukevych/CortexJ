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
                return java.util.concurrent.Executors.newFixedThreadPool(
                        Math.max(2, Runtime.getRuntime().availableProcessors()));
            }
        };
    }
}
