module io.cortexj.starter.java21 {
    requires transitive io.cortexj.api;
    requires transitive io.cortexj.core;
    requires io.cortexj.platform.java8;
    requires io.cortexj.platform.java11;
    requires io.cortexj.platform.java17;
    requires io.cortexj.platform.java21;
    requires io.cortexj.repository.local;
    requires io.cortexj.repository.hf;
    requires io.cortexj.runtime.remote;
    requires io.cortexj.runtime.onnx;
    requires io.cortexj.runtime.llamacpp;
    exports io.cortexj.starter.java21;
    provides io.cortexj.StarterInfo with io.cortexj.starter.java21.Java21StarterInfo;
}
