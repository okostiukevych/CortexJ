package io.cortexj.spi.runtime;

import io.cortexj.runtime.RuntimeId;

public interface ModelRuntime extends AutoCloseable {

    RuntimeId runtimeId();

    RuntimeModel load(RuntimeModelRequest request);

    @Override
    void close();
}
