module io.cortexj.platform.java11 {
    requires transitive io.cortexj.api;
    requires io.cortexj.spi.kit;
    exports io.cortexj.platform.java11;
    provides io.cortexj.spi.platform.PlatformModule
            with io.cortexj.platform.java11.Java11PlatformModule;
}
