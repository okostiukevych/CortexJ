package io.cortexj.starter.java11;

import io.cortexj.StarterInfo;

public final class Java11StarterInfo implements StarterInfo {

    @Override
    public String starterId() {
        return "cortexj-starter-java11";
    }

    @Override
    public int requiredFeatureVersion() {
        return 11;
    }
}
