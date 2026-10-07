module io.cortexj.core {
    requires transitive io.cortexj.api;
    requires transitive io.cortexj.spi.kit;
    requires transitive io.cortexj.model;
    requires transitive io.cortexj.hardware;
    requires transitive io.cortexj.cache;
    requires transitive io.cortexj.planner;
    requires io.cortexj.streaming;
    // audit 2026-10-07 (#148): io.cortexj.core is an internal implementation
    // namespace per docs/api-stability.md - not exported. The platform entry
    // point stays reachable through the ServiceLoader-provided
    // io.cortexj.PlatformFactory, which needs no export.
    provides io.cortexj.PlatformFactory with io.cortexj.core.DefaultPlatformFactory;
}
