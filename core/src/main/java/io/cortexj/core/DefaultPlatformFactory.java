package io.cortexj.core;

import io.cortexj.CortexJ;
import io.cortexj.CortexJBuilder;
import io.cortexj.PlatformFactory;

public final class DefaultPlatformFactory implements PlatformFactory {

    @Override
    public CortexJ create(CortexJBuilder configuration) {
        return new DefaultCortexJ(configuration);
    }
}
