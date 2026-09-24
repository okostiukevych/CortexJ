package io.cortexj.starter.java21;

import io.cortexj.StarterInfo;

public final class Java21StarterInfo implements StarterInfo {

    @Override
    public String starterId() {
        return "cortexj-starter-java21";
    }

    @Override
    public int requiredFeatureVersion() {
        return 21;
    }
}
