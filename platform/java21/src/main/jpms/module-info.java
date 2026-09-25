module io.cortexj.platform.java21 {
    requires transitive io.cortexj.api;
    requires io.cortexj.spi.kit;
    exports io.cortexj.platform.java21;
    provides io.cortexj.spi.platform.PlatformModule
            with io.cortexj.platform.java21.Java21PlatformModule;
}
