module io.cortexj.starter.java8 {
    requires transitive io.cortexj.api;
    requires transitive io.cortexj.core;
    requires io.cortexj.platform.java8;
    requires io.cortexj.repository.local;
    requires io.cortexj.repository.hf;
    requires io.cortexj.runtime.remote;
    requires io.cortexj.runtime.onnx;
    requires io.cortexj.runtime.llamacpp;
    exports io.cortexj.starter.java8;
    provides io.cortexj.StarterInfo with io.cortexj.starter.java8.Java8StarterInfo;
}
