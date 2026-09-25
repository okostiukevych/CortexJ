module io.cortexj.model {
    requires transitive io.cortexj.api;
    requires com.fasterxml.jackson.databind;
    exports io.cortexj.model.artifact;
    exports io.cortexj.model.descriptor;
    exports io.cortexj.model.token;
}
