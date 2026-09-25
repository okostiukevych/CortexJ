module io.cortexj.runtime.llamacpp {
    requires transitive io.cortexj.api;
    requires transitive io.cortexj.model;
    requires com.fasterxml.jackson.databind;
    exports io.cortexj.runtime.llamacpp;
    provides io.cortexj.spi.runtime.RuntimeProvider
            with io.cortexj.runtime.llamacpp.LlamaCppProvider;
}
