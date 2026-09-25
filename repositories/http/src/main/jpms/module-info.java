module io.cortexj.repository.http {
    requires transitive io.cortexj.api;
    requires transitive io.cortexj.model;
    exports io.cortexj.repository.http;
    provides io.cortexj.spi.repository.ModelRepositoryProvider
            with io.cortexj.repository.http.HttpRepositoryProvider;
}
