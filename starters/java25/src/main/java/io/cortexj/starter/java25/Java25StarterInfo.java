package io.cortexj.starter.java25;

import io.cortexj.StarterInfo;

public final class Java25StarterInfo implements StarterInfo {

    @Override
    public String starterId() {
        return "cortexj-starter-java25";
    }

    @Override
    public int requiredFeatureVersion() {
        return 25;
    }
}
