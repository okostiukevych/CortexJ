package io.cortexj.starter.java8;

import io.cortexj.StarterInfo;

public final class Java8StarterInfo implements StarterInfo {

    @Override
    public String starterId() {
        return "cortexj-starter-java8";
    }

    @Override
    public int requiredFeatureVersion() {
        return 8;
    }
}
