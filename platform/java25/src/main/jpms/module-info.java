module io.cortexj.platform.java25 {
    requires transitive io.cortexj.api;
    requires io.cortexj.spi.kit;
    exports io.cortexj.platform.java25;
    provides io.cortexj.spi.platform.PlatformModule
            with io.cortexj.platform.java25.Java25PlatformModule;
}
