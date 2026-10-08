package io.cortexj.provenance;

import io.cortexj.meta.ModelIdentity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Versioned answer to "what produced this artifact" (ADR-020):
 * producer, model revision, dataset fingerprint, code/platform version,
 * parameters, seeds, backend/hardware identity, parent artifacts and
 * checksums. Missing data is explicit (empty, never fabricated).
 * Java 8 compatible, Jackson-free — serialization lives in adapters.
 */
public final class ProvenanceManifest {

    public static final String SCHEMA_VERSION = "1";

    private final String schemaVersion;
    private final String producer;
    private final ModelIdentity model;
    private final String datasetFingerprint;
    private final String platformVersion;
    private final String jdk;
    private final String osArch;
    private final String backend;
    private final String hardware;
    private final Map<String, String> parameters;
    private final Map<String, Long> seeds;
    private final List<String> parentArtifacts;
    private final Map<String, String> checksums;
    private final long createdAtMillis;

    private ProvenanceManifest(Builder builder) {
        if (builder.model == null) {
            throw new IllegalArgumentException("provenance model must not be null");
        }
        this.schemaVersion = SCHEMA_VERSION;
        this.producer = orEmpty(builder.producer);
        this.model = builder.model;
        this.datasetFingerprint = orEmpty(builder.datasetFingerprint);
        this.platformVersion = orEmpty(builder.platformVersion);
        this.jdk = orEmpty(builder.jdk);
        this.osArch = orEmpty(builder.osArch);
        this.backend = orEmpty(builder.backend);
        this.hardware = orEmpty(builder.hardware);
        this.parameters = Collections.unmodifiableMap(new LinkedHashMap<String, String>(builder.parameters));
        this.seeds = Collections.unmodifiableMap(new LinkedHashMap<String, Long>(builder.seeds));
        this.parentArtifacts = Collections.unmodifiableList(new ArrayList<String>(builder.parentArtifacts));
        this.checksums = Collections.unmodifiableMap(new LinkedHashMap<String, String>(builder.checksums));
        this.createdAtMillis = builder.createdAtMillis;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String schemaVersion() {
        return schemaVersion;
    }

    public String producer() {
        return producer;
    }

    public ModelIdentity model() {
        return model;
    }

    public String datasetFingerprint() {
        return datasetFingerprint;
    }

    public String platformVersion() {
        return platformVersion;
    }

    public String jdk() {
        return jdk;
    }

    public String osArch() {
        return osArch;
    }

    public String backend() {
        return backend;
    }

    public String hardware() {
        return hardware;
    }

    public Map<String, String> parameters() {
        return parameters;
    }

    public Map<String, Long> seeds() {
        return seeds;
    }

    public List<String> parentArtifacts() {
        return parentArtifacts;
    }

    public Map<String, String> checksums() {
        return checksums;
    }

    public long createdAtMillis() {
        return createdAtMillis;
    }

    public boolean checksumPresent() {
        return !checksums.isEmpty();
    }

    public static final class Builder {

        private String producer;
        private ModelIdentity model;
        private String datasetFingerprint;
        private String platformVersion;
        private String jdk;
        private String osArch;
        private String backend;
        private String hardware;
        private final Map<String, String> parameters = new LinkedHashMap<String, String>();
        private final Map<String, Long> seeds = new LinkedHashMap<String, Long>();
        private final List<String> parentArtifacts = new ArrayList<String>();
        private final Map<String, String> checksums = new LinkedHashMap<String, String>();
        private long createdAtMillis = System.currentTimeMillis();

        public Builder producer(String producer) {
            this.producer = producer;
            return this;
        }

        public Builder model(ModelIdentity model) {
            this.model = model;
            return this;
        }

        public Builder datasetFingerprint(String datasetFingerprint) {
            this.datasetFingerprint = datasetFingerprint;
            return this;
        }

        public Builder platformVersion(String platformVersion) {
            this.platformVersion = platformVersion;
            return this;
        }

        public Builder jdk(String jdk) {
            this.jdk = jdk;
            return this;
        }

        public Builder osArch(String osArch) {
            this.osArch = osArch;
            return this;
        }

        public Builder backend(String backend) {
            this.backend = backend;
            return this;
        }

        public Builder hardware(String hardware) {
            this.hardware = hardware;
            return this;
        }

        public Builder parameter(String key, String value) {
            if (key != null && value != null) {
                parameters.put(key, value);
            }
            return this;
        }

        public Builder seed(String key, long value) {
            if (key != null) {
                seeds.put(key, Long.valueOf(value));
            }
            return this;
        }

        public Builder parentArtifact(String parentArtifact) {
            if (parentArtifact != null) {
                parentArtifacts.add(parentArtifact);
            }
            return this;
        }

        public Builder checksum(String artifact, String checksum) {
            if (artifact != null && checksum != null) {
                checksums.put(artifact, checksum);
            }
            return this;
        }

        public Builder createdAtMillis(long createdAtMillis) {
            this.createdAtMillis = createdAtMillis;
            return this;
        }

        public ProvenanceManifest build() {
            return new ProvenanceManifest(this);
        }
    }
}
