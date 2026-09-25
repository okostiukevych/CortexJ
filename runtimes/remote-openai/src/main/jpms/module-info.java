module io.cortexj.runtime.remote {
    requires transitive io.cortexj.api;
    requires io.cortexj.streaming;
    requires com.fasterxml.jackson.databind;
    exports io.cortexj.runtime.remote;
    provides io.cortexj.spi.runtime.RuntimeProvider
            with io.cortexj.runtime.remote.RemoteOpenAiProvider;
}
