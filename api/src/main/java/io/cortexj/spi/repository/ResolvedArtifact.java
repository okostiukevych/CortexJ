package io.cortexj.spi.repository;

import io.cortexj.model.ModelArtifact;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public interface ResolvedArtifact {

    ModelArtifact artifact();

    URI location();

    Map<String, String> downloadHeaders();

    abstract class Abstract implements ResolvedArtifact {

        protected final ModelArtifact artifact;
        protected final URI location;
        protected final Map<String, String> headers;

        protected Abstract(ModelArtifact artifact, URI location, Map<String, String> headers) {
            this.artifact = artifact;
            this.location = location;
            this.headers = headers == null
                    ? Collections.<String, String>emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<String, String>(headers));
        }

        @Override
        public ModelArtifact artifact() {
            return artifact;
        }

        @Override
        public URI location() {
            return location;
        }

        @Override
        public Map<String, String> downloadHeaders() {
            return headers;
        }

        @Override
        public String toString() {
            return artifact + " @ " + location;
        }
    }
}
