module io.cortexj.runtime.djl {
    requires transitive io.cortexj.api;
    requires transitive io.cortexj.model;
    requires ai.djl.api;
    exports io.cortexj.runtime.djl;
    provides io.cortexj.spi.runtime.RuntimeProvider
            with io.cortexj.runtime.djl.DjlProvider;
}
