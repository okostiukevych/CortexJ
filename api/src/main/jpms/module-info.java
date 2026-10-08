open module io.cortexj.api {
    exports io.cortexj;
    exports io.cortexj.diagnostics;
    exports io.cortexj.error;
    exports io.cortexj.hardware;
    exports io.cortexj.inference;
    exports io.cortexj.model;
    exports io.cortexj.observability;
    exports io.cortexj.planner;
    exports io.cortexj.runtime;
    exports io.cortexj.spi.artifact;
    exports io.cortexj.spi.conversion;
    exports io.cortexj.spi.platform;
    exports io.cortexj.spi.policy;
    exports io.cortexj.spi.repository;
    exports io.cortexj.spi.runtime;
    exports io.cortexj.stream;
    // audit 2026-10-08 (JPMS smoke): consumers still declare their own
    // `uses`, but stating it here keeps jlink service binding working when
    // api is a root module
    uses io.cortexj.PlatformFactory;
}
