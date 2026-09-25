module io.cortexj.core {
    requires transitive io.cortexj.api;
    requires transitive io.cortexj.spi.kit;
    requires transitive io.cortexj.model;
    requires transitive io.cortexj.hardware;
    requires transitive io.cortexj.cache;
    requires transitive io.cortexj.planner;
    requires io.cortexj.streaming;
    exports io.cortexj.core;
    provides io.cortexj.PlatformFactory with io.cortexj.core.DefaultPlatformFactory;
}
