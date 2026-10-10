package io.cortexj.planner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ModelLockfile {

    /**
     * Lockfile schema version (ADR-020, issue #159): {@code major}
     * gates compatibility, {@code minor} is forward-compatible. Readers
     * reject unknown majors with a typed error instead of misreading the
     * file; cross-track provenance (ADR-020) keys off the same scheme.
     */
    public static final int SCHEMA_MAJOR = 1;
    public static final int SCHEMA_MINOR = 0;

    private final String model;
    private final String schemaVersion;
    private final String revision;
    private final String artifactFile;
    private final String artifactSha256;
    private final String runtimeId;
    private final String runtimeVersion;
    private final String platformVersion;
    private final String plannerDigest;

    private ModelLockfile(Builder builder) {
        this.model = builder.model;
        this.schemaVersion = builder.schemaVersion == null
                ? SCHEMA_MAJOR + "." + SCHEMA_MINOR
                : builder.schemaVersion;
        this.revision = builder.revision;
        this.artifactFile = builder.artifactFile;
        this.artifactSha256 = builder.artifactSha256;
        this.runtimeId = builder.runtimeId;
        this.runtimeVersion = builder.runtimeVersion;
        this.platformVersion = builder.platformVersion;
        this.plannerDigest = builder.plannerDigest;
    }

    public static Builder builder(String model) {
        return new Builder(model);
    }

    public String model() {
        return model;
    }

    public String schemaVersion() {
        return schemaVersion;
    }

    public String revision() {
        return revision;
    }

    public String artifactFile() {
        return artifactFile;
    }

    public String artifactSha256() {
        return artifactSha256;
    }

    public String runtimeId() {
        return runtimeId;
    }

    public String runtimeVersion() {
        return runtimeVersion;
    }

    public String platformVersion() {
        return platformVersion;
    }

    public String plannerDigest() {
        return plannerDigest;
    }

    public boolean pinsArtifact() {
        return artifactFile != null && !artifactFile.isEmpty();
    }

    public boolean pinsRuntime() {
        return runtimeId != null && !runtimeId.isEmpty();
    }

    public ModelLockfile withRuntimeId(String newRuntimeId) {
        return builder(model)
                .schemaVersion(schemaVersion)
                .revision(revision)
                .artifactFile(artifactFile)
                .artifactSha256(artifactSha256)
                .runtimeId(newRuntimeId)
                .runtimeVersion(runtimeVersion)
                .platformVersion(platformVersion)
                .plannerDigest(plannerDigest)
                .build();
    }

    public String toYaml() {
        StringBuilder sb = new StringBuilder();
        sb.append("lockfileVersion: ").append(schemaVersion).append('\n');
        sb.append("model: ").append(model).append('\n');
        if (revision != null) {
            sb.append("revision: ").append(revision).append('\n');
        }
        if (artifactFile != null || artifactSha256 != null) {
            sb.append("artifact:\n");
            if (artifactFile != null) {
                sb.append("  file: ").append(artifactFile).append('\n');
            }
            if (artifactSha256 != null) {
                sb.append("  sha256: ").append(artifactSha256).append('\n');
            }
        }
        if (runtimeId != null || runtimeVersion != null) {
            sb.append("runtime:\n");
            if (runtimeId != null) {
                sb.append("  id: ").append(runtimeId).append('\n');
            }
            if (runtimeVersion != null) {
                sb.append("  version: ").append(runtimeVersion).append('\n');
            }
        }
        sb.append("planner:\n");
        sb.append("  platformVersion: ").append(platformVersion == null ? "" : platformVersion).append('\n');
        if (plannerDigest != null) {
            sb.append("  digest: ").append(plannerDigest).append('\n');
        }
        return sb.toString();
    }

    public void writeTo(Path path) throws IOException {
        java.nio.file.Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(path, toYaml().getBytes(StandardCharsets.UTF_8));
    }

    public static ModelLockfile readFrom(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        return parse(lines);
    }

    public static ModelLockfile parse(List<String> lines) {
        Map<String, String> flat = new LinkedHashMap<String, String>();
        List<String> errors = new ArrayList<String>();
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            if (raw.trim().isEmpty() || raw.trim().startsWith("#")) {
                continue;
            }
            int colon = raw.indexOf(':');
            if (colon < 0) {
                errors.add("line " + (i + 1) + " is not 'key: value': " + raw);
                continue;
            }
            String key = raw.substring(0, colon).trim();
            String value = raw.substring(colon + 1).trim();
            if (value.isEmpty()) {
                continue;
            }
            if (raw.startsWith(" ")) {
                flat.put("nested." + key, value);
            } else {
                flat.put(key, value);
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Malformed lockfile: " + errors.get(0));
        }
        String model = flat.get("model");
        if (model == null || model.isEmpty()) {
            throw new IllegalArgumentException("Lockfile is missing the required 'model' entry");
        }
        String schemaVersion = flat.get("lockfileVersion");
        if (schemaVersion != null && !schemaVersion.trim().isEmpty()) {
            int major = majorOf(schemaVersion);
            if (major < 0) {
                throw new IllegalArgumentException(
                        "Lockfile has an unparsable schema version '" + schemaVersion
                                + "'; expected '<major>.<minor>' starting at 1.0.");
            }
            if (major != SCHEMA_MAJOR) {
                throw new IllegalArgumentException(
                        "Unsupported lockfile schema major version " + major
                                + " (this build reads " + SCHEMA_MAJOR + ".x); regenerate the "
                                + "lockfile with a compatible CortexJ version.");
            }
        }
        return builder(model)
                .schemaVersion(schemaVersion)
                .revision(flat.get("revision"))
                .artifactFile(flat.get("nested.file"))
                .artifactSha256(flat.get("nested.sha256"))
                .runtimeId(flat.get("nested.id"))
                .runtimeVersion(flat.get("nested.version"))
                .platformVersion(flat.get("nested.platformVersion"))
                .plannerDigest(flat.get("nested.digest"))
                .build();
    }

    private static int majorOf(String schemaVersion) {
        String raw = schemaVersion.trim();
        int dot = raw.indexOf('.');
        String major = dot < 0 ? raw : raw.substring(0, dot);
        try {
            return Integer.parseInt(major.trim());
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    public static ModelLockfile fromPlan(ExecutionPlan plan) {        String artifactId = plan.selected() == null || plan.selected().artifact() == null
                ? null
                : plan.selected().artifact().id();
        String artifactSha = plan.selected() == null
                || plan.selected().artifact() == null
                || plan.selected().artifact().checksum() == null
                ? null
                : plan.selected().artifact().checksum().value();
        String runtimeId = plan.selected() == null ? null : plan.selected().runtimeId();
        return builder(plan.modelId())
                .revision(plan.resolvedRevision())
                .artifactFile(artifactId)
                .artifactSha256(artifactSha)
                .runtimeId(runtimeId)
                .runtimeVersion(runtimeId == null ? null : "selected")
                .platformVersion(plan.platformVersion())
                .plannerDigest(plan.inputsDigest())
                .build();
    }

    public static final class Builder {

        private final String model;
        private String schemaVersion;
        private String revision;
        private String artifactFile;
        private String artifactSha256;
        private String runtimeId;
        private String runtimeVersion;
        private String platformVersion;
        private String plannerDigest;

        private Builder(String model) {
            if (model == null || model.trim().isEmpty()) {
                throw new IllegalArgumentException("Lockfile model must not be empty");
            }
            this.model = model.trim();
        }

        public Builder revision(String value) {
            this.revision = value;
            return this;
        }

        public Builder schemaVersion(String value) {
            this.schemaVersion = value;
            return this;
        }

        public Builder artifactFile(String value) {
            this.artifactFile = value;
            return this;
        }

        public Builder artifactSha256(String value) {
            this.artifactSha256 = value;
            return this;
        }

        public Builder runtimeId(String value) {
            this.runtimeId = value == null ? null : value.trim().toLowerCase();
            return this;
        }

        public Builder runtimeVersion(String value) {
            this.runtimeVersion = value;
            return this;
        }

        public Builder platformVersion(String value) {
            this.platformVersion = value;
            return this;
        }

        public Builder plannerDigest(String value) {
            this.plannerDigest = value;
            return this;
        }

        public ModelLockfile build() {
            return new ModelLockfile(this);
        }
    }
}
