package io.cortexj.platform.java8;

import io.cortexj.spi.platform.PlatformModule;
import io.cortexj.tck.PlatformModuleContract;

/**
 * Audit 2026-10-07 (#148): the LTS executor contract, exercised.
 */
class Java8PlatformModuleContractTest extends PlatformModuleContract {

    @Override
    protected PlatformModule module() {
        return new Java8PlatformModule();
    }
}
