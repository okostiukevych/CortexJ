module io.cortexj.repository.hf {
    requires transitive io.cortexj.api;
    requires transitive io.cortexj.model;
    requires com.fasterxml.jackson.databind;
    exports io.cortexj.repository.hf;
    provides io.cortexj.spi.repository.ModelRepositoryProvider
            with io.cortexj.repository.hf.HuggingFaceRepositoryProvider;
}
