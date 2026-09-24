package io.cortexj.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public interface ModelArtifact {

    String id();

    ArtifactFormat format();

    long sizeBytes();

    Checksum checksum();

    Map<String, String> metadata();

    abstract class Abstract implements ModelArtifact {

        protected final String id;
        protected final ArtifactFormat format;
        protected final long sizeBytes;
        protected final Checksum checksum;
        protected final Map<String, String> metadata;

        protected Abstract(String id, ArtifactFormat format, long sizeBytes, Checksum checksum, Map<String, String> metadata) {
            if (id == null || id.trim().isEmpty()) {
                throw new IllegalArgumentException("Artifact id must not be empty");
            }
            this.id = id;
            this.format = format == null ? ArtifactFormat.UNKNOWN : format;
            this.sizeBytes = sizeBytes;
            this.checksum = checksum;
            this.metadata = metadata == null
                    ? Collections.<String, String>emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<String, String>(metadata));
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public ArtifactFormat format() {
            return format;
        }

        @Override
        public long sizeBytes() {
            return sizeBytes;
        }

        @Override
        public Checksum checksum() {
            return checksum;
        }

        @Override
        public Map<String, String> metadata() {
            return metadata;
        }

        @Override
        public String toString() {
            return id + " (" + format + ", " + sizeBytes + " bytes)";
        }
    }
}
