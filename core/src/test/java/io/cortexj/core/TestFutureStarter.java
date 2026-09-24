package io.cortexj.core;

import io.cortexj.CortexJ;
import io.cortexj.StarterInfo;

public final class TestFutureStarter implements StarterInfo {

    @Override
    public String starterId() {
        return "cortexj-starter-test-future";
    }

    @Override
    public int requiredFeatureVersion() {
        String required = System.getProperty("cortexj.test.starter.requires", "8");
        return Integer.parseInt(required);
    }
}
