module io.cortexj.convert.gguf {
    requires transitive io.cortexj.api;
    requires transitive io.cortexj.model;
    requires io.cortexj.cache;
    exports io.cortexj.convert.gguf;
    provides io.cortexj.spi.conversion.ModelConverter
            with io.cortexj.convert.gguf.GgufFloatConverter;
}
