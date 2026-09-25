module io.cortexj.repository.local {
    requires transitive io.cortexj.api;
    requires transitive io.cortexj.model;
    requires io.cortexj.cache;
    requires com.fasterxml.jackson.databind;
    exports io.cortexj.repository.local;
    exports io.cortexj.repository.local.bundle;
    provides io.cortexj.spi.repository.ModelRepositoryProvider
            with io.cortexj.repository.local.LocalRepositoryProvider;
}
