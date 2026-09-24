package io.cortexj.starter.java17;

import io.cortexj.StarterInfo;

public final class Java17StarterInfo implements StarterInfo {

    @Override
    public String starterId() {
        return "cortexj-starter-java17";
    }

    @Override
    public int requiredFeatureVersion() {
        return 17;
    }
}
