module io.cortexj.platform.java17 {
    requires transitive io.cortexj.api;
    requires io.cortexj.spi.kit;
    exports io.cortexj.platform.java17;
    provides io.cortexj.spi.platform.PlatformModule
            with io.cortexj.platform.java17.Java17PlatformModule;
}
