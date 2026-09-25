module io.cortexj.platform.java8 {
    requires transitive io.cortexj.api;
    requires io.cortexj.spi.kit;
    exports io.cortexj.platform.java8;
    provides io.cortexj.spi.platform.PlatformModule
            with io.cortexj.platform.java8.Java8PlatformModule;
}
